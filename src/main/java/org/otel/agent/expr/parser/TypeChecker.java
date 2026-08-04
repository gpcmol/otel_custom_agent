package org.otel.agent.expr.parser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.expr.And;
import org.otel.agent.expr.Arithmetic;
import org.otel.agent.expr.BoolCall;
import org.otel.agent.expr.Compare;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.ExpressionCompileException;
import org.otel.agent.expr.In;
import org.otel.agent.expr.Leaf;
import org.otel.agent.expr.Literal;
import org.otel.agent.expr.Not;
import org.otel.agent.expr.Or;
import org.otel.agent.expr.Property;
import org.otel.agent.expr.SizeCall;

/**
 * Parse-time reflective type checker and AST transformer for condition DSL expressions.
 *
 * <p>Walks the AST, reflects on getter return types to derive and verify the type of each leaf,
 * operator, and function call, and returns a new AST with correct {@code operandType} fields on
 * {@link Compare} nodes and {@code resultType} on {@link Arithmetic} nodes. Type mismatches throw
 * {@link ExpressionCompileException} with a named category.
 */
final class TypeChecker {

  private final String blockId;
  private final Class<?> declaringClass;
  private final Method exitMethod;

  TypeChecker(final String blockId, final Class<?> declaringClass, final Method exitMethod) {
    this.blockId = blockId;
    this.declaringClass = declaringClass;
    this.exitMethod = exitMethod;
  }

  Condition check(final Condition condition) throws ExpressionCompileException {
    return checkCondition(condition);
  }

  private Condition checkCondition(final Condition c) throws ExpressionCompileException {
    return switch (c) {
      case And and -> {
        final List<Condition> terms = new ArrayList<>();
        for (final Condition t : and.terms()) terms.add(checkCondition(t));
        yield new And(terms);
      }
      case Or or -> {
        final List<Condition> terms = new ArrayList<>();
        for (final Condition t : or.terms()) terms.add(checkCondition(t));
        yield new Or(terms);
      }
      case Not not -> new Not(checkCondition(not.term()));
      case Compare cmp -> checkCompare(cmp);
      case In in -> checkIn(in);
      case BoolCall call -> checkBoolCall(call);
    };
  }

  private Condition checkCompare(final Compare cmp) throws ExpressionCompileException {
    final Class<?> leftType = leafType(cmp.left());
    final Class<?> rightType = leafType(cmp.right());

    // Null literal on either side is always OK for == and !=
    if (isNullLiteral(cmp.right())) {
      if (cmp.op() != Compare.Op.EQ && cmp.op() != Compare.Op.NE) {
        throw new ExpressionCompileException(blockId, "type mismatch",
            "null only valid with == or !=, got " + cmp.op());
      }
      return cmp;
    }
    if (isNullLiteral(cmp.left())) {
      if (cmp.op() != Compare.Op.EQ && cmp.op() != Compare.Op.NE) {
        throw new ExpressionCompileException(blockId, "type mismatch",
            "null only valid with == or !=, got " + cmp.op());
      }
      return cmp;
    }

    // Boolean implicit comparison: bare boolean leaf == true
    if (isBooleanTrueLiteral(cmp.right())
        && (leftType == boolean.class || leftType == Boolean.class)) {
      return new Compare(cmp.op(), cmp.left(), cmp.right(), Boolean.class);
    }

    final Class<?> normLeft = normalizeNumeric(leftType);
    final Class<?> normRight = normalizeNumeric(rightType);

    if (normLeft != normRight) {
      throw new ExpressionCompileException(blockId, "type mismatch",
          leftType.getSimpleName() + " vs " + rightType.getSimpleName());
    }

    // < > <= >= only valid for numeric
    if (cmp.op() == Compare.Op.LT || cmp.op() == Compare.Op.GT
        || cmp.op() == Compare.Op.LE || cmp.op() == Compare.Op.GE) {
      if (normLeft != Long.class && normLeft != Double.class) {
        throw new ExpressionCompileException(blockId, "type mismatch",
            "comparison " + cmp.op() + " requires numeric, got " + leftType.getSimpleName());
      }
    }

    return new Compare(cmp.op(), cmp.left(), cmp.right(), normLeft);
  }

  private Condition checkIn(final In in) throws ExpressionCompileException {
    final Class<?> valueType = leafType(in.value());
    final Class<?> normValue = normalizeNumeric(valueType);
    for (final Literal lit : in.literals()) {
      final Class<?> litType = normalizeNumeric(lit.type());
      if (litType != normValue && lit.type() != Object.class) {
        throw new ExpressionCompileException(blockId, "type mismatch",
            "in: " + valueType.getSimpleName() + " vs " + lit.type().getSimpleName());
      }
    }
    return in;
  }

  private Condition checkBoolCall(final BoolCall call) throws ExpressionCompileException {
    final Class<?> leftType = leafType(call.left());
    final Class<?> rightType = leafType(call.right());

    switch (call.fn()) {
      case CONTAINS_STRING, ICONTAINS_STRING -> {
        if (leftType != String.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires String, got " + leftType.getSimpleName());
        }
        if (rightType != String.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires String needle, got " + rightType.getSimpleName());
        }
      }
      case CONTAINS_COLLECTION, ICONTAINS_COLLECTION -> {
        if (!Collection.class.isAssignableFrom(leftType) && leftType != Object.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires Collection, got " + leftType.getSimpleName());
        }
        if (rightType != String.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires String needle, got " + rightType.getSimpleName());
        }
      }
      case LIKE, ILIKE -> {
        if (leftType != String.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires String, got " + leftType.getSimpleName());
        }
        if (rightType != String.class) {
          throw new ExpressionCompileException(blockId, "type mismatch",
              call.fn() + " requires String pattern, got " + rightType.getSimpleName());
        }
      }
    }
    return call;
  }

  private Class<?> leafType(final Leaf leaf) throws ExpressionCompileException {
    return switch (leaf) {
      case Literal lit -> lit.type() == null ? Object.class : lit.type();
      case Property prop -> resolvePropertyType(prop);
      case SizeCall ignored -> Long.class;
      case Arithmetic ar -> ar.resultType() != Object.class ? ar.resultType() : Long.class;
    };
  }

  private Class<?> resolvePropertyType(final Property prop) throws ExpressionCompileException {
    final Class<?> rootType = resolveRootType(prop);
    if (prop.segments().isEmpty()) return rootType;
    Class<?> current = rootType;
    for (int i = 0; i < prop.segments().size(); i++) {
      if (current == null || current == Object.class) return Object.class;
      if (current == Map.class) {
        throw new ExpressionCompileException(blockId, "map unsupported",
            "Map traversal not supported");
      }
      if (Collection.class.isAssignableFrom(current) || current.isArray()) {
        current = resolveCollectionElementType(current);
        if (current == null || current == Object.class) {
          throw new ExpressionCompileException(blockId, "erased collection type",
              "raw collection at segment " + i);
        }
      }
      final String propertyName;
      if (prop.segments().get(i) instanceof PropertySegment ps) {
        propertyName = ps.propertyName();
      } else if (prop.segments().get(i) instanceof IndexedPropertySegment ips) {
        propertyName = ips.propertyName();
      } else {
        throw new ExpressionCompileException(blockId, "syntax error", "unknown segment type");
      }
      current = resolveGetterReturnType(current, propertyName);
      if (current == null) {
        throw new ExpressionCompileException(blockId, "unknown property",
            "property '" + propertyName + "' not found");
      }
    }
    return current;
  }

  private Class<?> resolveRootType(final Property prop) {
    return switch (prop.root()) {
      case RootSource.This ignored -> declaringClass;
      case RootSource.Argument arg -> {
        final Class<?>[] params = exitMethod.getParameterTypes();
        yield arg.index() < params.length ? params[arg.index()] : Object.class;
      }
      case RootSource.ReturnValue ignored ->
          exitMethod.getReturnType() == void.class ? Object.class : exitMethod.getReturnType();
    };
  }

  private Class<?> resolveGetterReturnType(final Class<?> type, final String property) {
    final String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    try {
      final Method getter = type.getMethod("get" + suffix);
      if (getter.getReturnType() != void.class) return getter.getReturnType();
    } catch (final NoSuchMethodException ignored) {
    }
    try {
      final Method boolGetter = type.getMethod("is" + suffix);
      if (boolGetter.getReturnType() == boolean.class || boolGetter.getReturnType() == Boolean.class) {
        return boolGetter.getReturnType();
      }
    } catch (final NoSuchMethodException ignored) {
    }
    try {
      return type.getField(property).getType();
    } catch (final NoSuchFieldException ignored) {
      return null;
    }
  }

  private Class<?> resolveCollectionElementType(final Class<?> collectionType) {
    if (collectionType.isArray()) return collectionType.getComponentType();
    return Object.class;
  }

  private Class<?> normalizeNumeric(final Class<?> type) {
    if (type == byte.class || type == Byte.class
        || type == short.class || type == Short.class
        || type == int.class || type == Integer.class
        || type == long.class || type == Long.class) return Long.class;
    if (type == float.class || type == Float.class
        || type == double.class || type == Double.class) return Double.class;
    return type;
  }

  private boolean isNullLiteral(final Leaf leaf) {
    return leaf instanceof Literal lit && lit.value() == null;
  }

  private boolean isBooleanTrueLiteral(final Leaf leaf) {
    return leaf instanceof Literal lit && Boolean.TRUE.equals(lit.value());
  }
}