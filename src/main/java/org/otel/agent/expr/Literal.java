package org.otel.agent.expr;

/**
 * Compile-time constant value (string, number, boolean, {@code null}).
 *
 * <p>Produces the same {@code value} on every {@link #read} call — no runtime resolution.
 */
public record Literal(Object value, Class<?> type) implements Leaf {
  @Override
  public Object read(final EvalContext ctx) {
    return value;
  }
}