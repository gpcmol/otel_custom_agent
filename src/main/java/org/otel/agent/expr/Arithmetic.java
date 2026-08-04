package org.otel.agent.expr;

/**
 * Binary arithmetic computation ({@code +}, {@code -}, {@code *}, {@code /}, {@code %}).
 *
 * <p>Produces a numeric value ({@code Long} or {@code Double} depending on {@code resultType}).
 * The {@code resultType} is determined at parse-time by the {@link
 * org.otel.agent.expr.parser.TypeChecker} — both operands must be numeric and the result type is
 * {@code Double} if either operand is floating-point, {@code Long} otherwise.
 */
public record Arithmetic(Arithmetic.Op op, Leaf left, Leaf right, Class<?> resultType)
    implements Leaf {

  /** Arithmetic operator. */
  public enum Op {
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT
  }

  @Override
  public Object read(final EvalContext ctx) {
    final Object lv = left.read(ctx);
    final Object rv = right.read(ctx);
    if (lv == null || rv == null) return null;
    if (resultType == Long.class) {
      final long ln = ((Number) lv).longValue();
      final long rn = ((Number) rv).longValue();
      return switch (op) {
        case PLUS -> ln + rn;
        case MINUS -> ln - rn;
        case STAR -> ln * rn;
        case SLASH -> rn == 0 ? null : ln / rn;
        case PERCENT -> rn == 0 ? null : ln % rn;
      };
    }
    if (resultType == Double.class) {
      final double ln = ((Number) lv).doubleValue();
      final double rn = ((Number) rv).doubleValue();
      return switch (op) {
        case PLUS -> ln + rn;
        case MINUS -> ln - rn;
        case STAR -> ln * rn;
        case SLASH -> ln / rn;
        case PERCENT -> rn == 0 ? null : ln % rn;
      };
    }
    return null;
  }
}