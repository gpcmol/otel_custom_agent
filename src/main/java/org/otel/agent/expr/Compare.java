package org.otel.agent.expr;

/**
 * Binary comparison ({@code ==}, {@code !=}, {@code <}, {@code >}, {@code <=}, {@code >=}).
 *
 * <p>The {@code operandType} is determined at parse-time by the {@link
 * org.otel.agent.expr.parser.TypeChecker} and fixed in the AST. The runtime evaluator dispatches
 * on this type — no {@code instanceof}, no coercion. The type-checker normalises all integral
 * types to {@code Long} and all floating-point types to {@code Double} at compile time.
 *
 * <p>Supported operand types:
 * <ul>
 *   <li>{@code String.class} — {@code ==} and {@code !=} only ({@code String.equals})
 *   <li>{@code Long.class} — all six operators (primitive long comparison)
 *   <li>{@code Double.class} — all six operators (primitive double comparison)
 *   <li>{@code Boolean.class} — {@code ==} and {@code !=} only
 * </ul>
 *
 * <p>Null handling (universal rule):
 * <ul>
 *   <li>If the right value is {@code null}: {@code ==} returns {@code true} iff left is
 *       {@code null}; {@code !=} returns {@code true} iff left is non-{@code null}; other
 *       operators return {@code false}.
 *   <li>If only the left value is {@code null} (right is non-null): all operators return
 *       {@code false}.
 * </ul>
 */
public record Compare(Compare.Op op, Leaf left, Leaf right, Class<?> operandType)
    implements Condition {

  /** Comparison operator. */
  public enum Op {
    EQ,
    NE,
    LT,
    GT,
    LE,
    GE
  }

  @Override
  public boolean eval(final EvalContext ctx) {
    final Object lv = left.read(ctx);
    final Object rv = right.read(ctx);
    if (rv == null) {
      return switch (op) {
        case EQ -> lv == null;
        case NE -> lv != null;
        default -> false;
      };
    }
    if (lv == null) return false;
    if (operandType == String.class) {
      final String ls = (String) lv;
      final String rs = (String) rv;
      return switch (op) {
        case EQ -> ls.equals(rs);
        case NE -> !ls.equals(rs);
        default -> false;
      };
    }
    if (operandType == Long.class) {
      final long ln = ((Number) lv).longValue();
      final long rn = ((Number) rv).longValue();
      return switch (op) {
        case EQ -> ln == rn;
        case NE -> ln != rn;
        case LT -> ln < rn;
        case GT -> ln > rn;
        case LE -> ln <= rn;
        case GE -> ln >= rn;
      };
    }
    if (operandType == Double.class) {
      final double ln = ((Number) lv).doubleValue();
      final double rn = ((Number) rv).doubleValue();
      return switch (op) {
        case EQ -> ln == rn;
        case NE -> ln != rn;
        case LT -> ln < rn;
        case GT -> ln > rn;
        case LE -> ln <= rn;
        case GE -> ln >= rn;
      };
    }
    if (operandType == Boolean.class) {
      final boolean lb = (Boolean) lv;
      final boolean rb = (Boolean) rv;
      return switch (op) {
        case EQ -> lb == rb;
        case NE -> lb != rb;
        default -> false;
      };
    }
    return false;
  }
}