package org.otel.agent.expr;

/**
 * Boolean negation ({@code !}) of a single sub-condition.
 *
 * <p>A {@code null} operand is treated as {@code false} for negation purposes, so {@code !null}
 * evaluates to {@code true}.
 */
public record Not(Condition term) implements Condition {
  @Override
  public boolean eval(final EvalContext ctx) {
    return !term.eval(ctx);
  }
}