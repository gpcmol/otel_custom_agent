package org.otel.agent.expr;

import java.util.Collection;

/**
 * Boolean function call ({@code contains}, {@code like}, {@code ilike}, {@code icontains}).
 *
 * <p>The {@code fn} variant encodes both the function name and the operand mode (String vs
 * Collection), chosen at parse-time by the {@link org.otel.agent.expr.parser.TypeChecker}. The
 * runtime dispatch is a single {@code switch} with no {@code instanceof} on runtime values.
 *
 * <ul>
 *   <li>{@code CONTAINS_STRING} — {@code String.contains} (substring match)
 *   <li>{@code CONTAINS_COLLECTION} — {@code Collection.contains} (element equality)
 *   <li>{@code LIKE} — SQL wildcard match (case-sensitive), via {@link LikeMatcher#like}
 *   <li>{@code ILIKE} — SQL wildcard match (case-insensitive), via {@link LikeMatcher#ilike}
 *   <li>{@code ICONTAINS_STRING} — {@code String.equalsIgnoreCase} (whole-string, NOT substring)
 *   <li>{@code ICONTAINS_COLLECTION} — any element {@code equalsIgnoreCase}
 * </ul>
 */
public record BoolCall(BoolCall.Fn fn, Leaf left, Leaf right) implements Condition {

  /** Function variant — encodes both function name and operand mode. */
  public enum Fn {
    CONTAINS_STRING,
    CONTAINS_COLLECTION,
    LIKE,
    ILIKE,
    ICONTAINS_STRING,
    ICONTAINS_COLLECTION
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean eval(final EvalContext ctx) {
    final Object l = left.read(ctx);
    final Object r = right.read(ctx);
    if (l == null || r == null) return false;
    return switch (fn) {
      case CONTAINS_STRING -> ((String) l).contains((String) r);
      case CONTAINS_COLLECTION -> ((Collection<Object>) l).contains(r);
      case LIKE -> LikeMatcher.like((String) l, (String) r);
      case ILIKE -> LikeMatcher.ilike((String) l, (String) r);
      // ponytail: icontains on String is whole-string equalsIgnoreCase, NOT substring.
      // For case-insensitive substring matching use ilike(hay, "%needle%").
      case ICONTAINS_STRING -> ((String) l).equalsIgnoreCase((String) r);
      case ICONTAINS_COLLECTION -> {
        boolean found = false;
        for (final Object elem : (Collection<Object>) l) {
          if (elem instanceof String es && es.equalsIgnoreCase((String) r)) {
            found = true;
            break;
          }
        }
        yield found;
      }
    };
  }
}