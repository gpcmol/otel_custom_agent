package org.otel.agent.expr.parser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PathSegment;
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
import org.otel.agent.expr.parser.Lexer.Token;

/**
 * Entry point for compiling an {@code expr} string into a typed {@link Condition} AST.
 *
 * <p>Called by {@link org.otel.agent.config.parser.ConfigurationParser} at startup. The parser
 * runs the {@link Lexer}, builds the AST via recursive-descent precedence-climbing, and runs the
 * {@link TypeChecker} against the declared exit-point class and method. On any failure it throws
 * {@link ExpressionCompileException} carrying the block id and a failure category.
 */
public final class ExpressionParser {

  private String blockId;
  private Class<?> declaringClass;
  private Method exitMethod;
  private List<Token> tokens;
  private int pos;

  public Condition parse(final String expr, final String blockId, final Class<?> declaringClass,
      final Method exitMethod) throws ExpressionCompileException {
    this.blockId = blockId;
    this.declaringClass = declaringClass;
    this.exitMethod = exitMethod;
    this.tokens = new Lexer().tokenize(expr);
    this.pos = 0;
    final Condition condition = parseOr();
    if (current().type() != Token.Type.EOF) {
      throw new ExpressionCompileException(blockId, "syntax error", "unexpected token: " + current().text());
    }
    return new TypeChecker(blockId, declaringClass, exitMethod).check(condition);
  }

  // --- Precedence: || → && → ! → primary → comparison → in → arithmetic → atom ---

  private Condition parseOr() throws ExpressionCompileException {
    final List<Condition> terms = new ArrayList<>();
    terms.add(parseAnd());
    while (current().type() == Token.Type.OR) {
      advance();
      terms.add(parseAnd());
    }
    return terms.size() == 1 ? terms.getFirst() : new Or(terms);
  }

  private Condition parseAnd() throws ExpressionCompileException {
    final List<Condition> terms = new ArrayList<>();
    terms.add(parseNot());
    while (current().type() == Token.Type.AND) {
      advance();
      terms.add(parseNot());
    }
    return terms.size() == 1 ? terms.getFirst() : new And(terms);
  }

  private Condition parseNot() throws ExpressionCompileException {
    if (current().type() == Token.Type.NOT) {
      advance();
      return new Not(parseNot());
    }
    return parsePrimary();
  }

  private Condition parsePrimary() throws ExpressionCompileException {
    if (current().type() == Token.Type.LPAREN) {
      advance();
      final Condition inner = parseOr();
      expect(Token.Type.RPAREN, ")");
      return inner;
    }
    if (current().type() == Token.Type.IDENTIFIER && isBooleanFunction(current().text())) {
      return parseBooleanFunctionCall();
    }
    return parseComparison();
  }

  private boolean isBooleanFunction(final String name) {
    return name.equals("contains") || name.equals("like") || name.equals("ilike") || name.equals("icontains");
  }

  private Condition parseComparison() throws ExpressionCompileException {
    final Leaf left = parseArithmetic();
    final Token.Type tt = current().type();
    if (tt == Token.Type.EQ || tt == Token.Type.NE
        || tt == Token.Type.LT || tt == Token.Type.GT
        || tt == Token.Type.LE || tt == Token.Type.GE) {
      final Compare.Op op = switch (tt) {
        case EQ -> Compare.Op.EQ;
        case NE -> Compare.Op.NE;
        case LT -> Compare.Op.LT;
        case GT -> Compare.Op.GT;
        case LE -> Compare.Op.LE;
        case GE -> Compare.Op.GE;
        default -> throw new IllegalStateException();
      };
      advance();
      final Leaf right = parseArithmetic();
      return new Compare(op, left, right, Object.class);
    }
    if (tt == Token.Type.IN) {
      advance();
      return parseIn(left);
    }
    // Bare boolean leaf (e.g. $arg0.electric) — implicit == true
    return new Compare(Compare.Op.EQ, left, new Literal(true, Boolean.class), Boolean.class);
  }

  private Condition parseIn(final Leaf value) throws ExpressionCompileException {
    expect(Token.Type.LBRACKET, "[");
    final List<Literal> literals = new ArrayList<>();
    literals.add(parseLiteral());
    while (current().type() == Token.Type.COMMA) {
      advance();
      literals.add(parseLiteral());
    }
    expect(Token.Type.RBRACKET, "]");
    return new In(value, literals, Object.class);
  }

  private Leaf parseArithmetic() throws ExpressionCompileException {
    Leaf left = parseAtom();
    while (isArithmeticOp(current().type())) {
      final Arithmetic.Op op = switch (current().type()) {
        case PLUS -> Arithmetic.Op.PLUS;
        case MINUS -> Arithmetic.Op.MINUS;
        case STAR -> Arithmetic.Op.STAR;
        case SLASH -> Arithmetic.Op.SLASH;
        case PERCENT -> Arithmetic.Op.PERCENT;
        default -> throw new IllegalStateException();
      };
      advance();
      final Leaf right = parseAtom();
      left = new Arithmetic(op, left, right, Object.class);
    }
    return left;
  }

  private boolean isArithmeticOp(final Token.Type type) {
    return type == Token.Type.PLUS || type == Token.Type.MINUS
        || type == Token.Type.STAR || type == Token.Type.SLASH
        || type == Token.Type.PERCENT;
  }

  private Leaf parseAtom() throws ExpressionCompileException {
    final Token t = current();
    return switch (t.type()) {
      case STRING_LITERAL -> { advance(); yield new Literal(t.text(), String.class); }
      case NUMBER_LITERAL -> { advance(); yield parseNumberLiteral(t.text()); }
      case TRUE -> { advance(); yield new Literal(true, Boolean.class); }
      case FALSE -> { advance(); yield new Literal(false, Boolean.class); }
      case NULL -> { advance(); yield new Literal(null, Object.class); }
      case DOLLAR_ROOT -> parseProperty();
      case IDENTIFIER -> parseFunctionCall();
      default -> throw new ExpressionCompileException(blockId, "syntax error",
          "unexpected token: " + t.text() + " (" + t.type() + ") at " + t.position());
    };
  }

  private Literal parseNumberLiteral(final String text) {
    if (text.contains(".")) {
      return new Literal(Double.parseDouble(text), Double.class);
    }
    return new Literal(Long.parseLong(text), Long.class);
  }

  private Leaf parseProperty() throws ExpressionCompileException {
    final Token rootToken = current();
    advance();
    final RootSource root = parseRootSource(rootToken.text());
    final List<PathSegment> segments = new ArrayList<>();
    while (current().type() == Token.Type.DOT) {
      advance();
      segments.add(parseSegment());
    }
    return new Property(root, segments, Object.class);
  }

  private RootSource parseRootSource(final String text) throws ExpressionCompileException {
    if (text.equals("$this")) return new RootSource.This();
    if (text.equals("$return")) return new RootSource.ReturnValue();
    if (text.startsWith("$arg")) {
      final int index = Integer.parseInt(text.substring(4));
      return new RootSource.Argument(index);
    }
    throw new ExpressionCompileException(blockId, "syntax error", "invalid root: " + text);
  }

  private PathSegment parseSegment() throws ExpressionCompileException {
    final Token nameToken = current();
    if (nameToken.type() != Token.Type.IDENTIFIER) {
      throw new ExpressionCompileException(blockId, "syntax error",
          "expected property name, got " + nameToken.text());
    }
    advance();
    if (current().type() == Token.Type.LBRACKET) {
      advance();
      final Token idxToken = current();
      if (idxToken.type() != Token.Type.NUMBER_LITERAL) {
        throw new ExpressionCompileException(blockId, "syntax error",
            "expected index, got " + idxToken.text());
      }
      advance();
      expect(Token.Type.RBRACKET, "]");
      return new IndexedPropertySegment(nameToken.text(), Integer.parseInt(idxToken.text()));
    }
    return new PropertySegment(nameToken.text());
  }

  private Condition parseBooleanFunctionCall() throws ExpressionCompileException {
    final Token nameToken = current();
    advance();
    expect(Token.Type.LPAREN, "(");
    final Leaf arg1 = parseArithmetic();
    final List<Leaf> args = new ArrayList<>();
    args.add(arg1);
    while (current().type() == Token.Type.COMMA) {
      advance();
      args.add(parseArithmetic());
    }
    expect(Token.Type.RPAREN, ")");
    if (args.size() != 2) {
      throw new ExpressionCompileException(blockId, "wrong arity",
          nameToken.text() + " expects 2 arguments, got " + args.size());
    }
    final BoolCall.Fn fn = switch (nameToken.text()) {
      case "contains" -> BoolCall.Fn.CONTAINS_COLLECTION;
      case "like" -> BoolCall.Fn.LIKE;
      case "ilike" -> BoolCall.Fn.ILIKE;
      case "icontains" -> BoolCall.Fn.ICONTAINS_COLLECTION;
      default -> throw new ExpressionCompileException(blockId, "unknown function",
          "unknown function: " + nameToken.text());
    };
    return new BoolCall(fn, args.get(0), args.get(1));
  }

  private Leaf parseFunctionCall() throws ExpressionCompileException {
    final Token nameToken = current();
    advance();
    expect(Token.Type.LPAREN, "(");
    final Leaf arg1 = parseArithmetic();
    final List<Leaf> args = new ArrayList<>();
    args.add(arg1);
    while (current().type() == Token.Type.COMMA) {
      advance();
      args.add(parseArithmetic());
    }
    expect(Token.Type.RPAREN, ")");
    return buildFunctionCall(nameToken.text(), args);
  }

  private Leaf buildFunctionCall(final String name, final List<Leaf> args)
      throws ExpressionCompileException {
    return switch (name) {
      case "size" -> {
        if (args.size() != 1) {
          throw new ExpressionCompileException(blockId, "wrong arity",
              "size expects 1 argument, got " + args.size());
        }
        yield new SizeCall(args.getFirst());
      }
      default -> throw new ExpressionCompileException(blockId, "unknown function",
          "unknown function: " + name);
    };
  }

  private Literal parseLiteral() throws ExpressionCompileException {
    final Token t = current();
    return switch (t.type()) {
      case STRING_LITERAL -> { advance(); yield new Literal(t.text(), String.class); }
      case NUMBER_LITERAL -> { advance(); yield parseNumberLiteral(t.text()); }
      case TRUE -> { advance(); yield new Literal(true, Boolean.class); }
      case FALSE -> { advance(); yield new Literal(false, Boolean.class); }
      case NULL -> { advance(); yield new Literal(null, Object.class); }
      default -> throw new ExpressionCompileException(blockId, "syntax error",
          "expected literal, got " + t.text());
    };
  }

  // --- Token helpers ---

  private Token current() {
    return tokens.get(pos);
  }

  private void advance() {
    if (pos < tokens.size() - 1) pos++;
  }

  private void expect(final Token.Type expected, final String display) throws ExpressionCompileException {
    if (current().type() != expected) {
      throw new ExpressionCompileException(blockId, "syntax error",
          "expected '" + display + "', got '" + current().text() + "' at " + current().position());
    }
    advance();
  }
}