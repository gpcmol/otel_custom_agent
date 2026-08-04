package org.otel.agent.expr;

/**
 * Sealed AST node representing a compiled boolean expression from the condition DSL.
 *
 * <p>Produced by {@link org.otel.agent.expr.parser.ExpressionParser} at startup and evaluated
 * once per exit event by {@link org.otel.agent.runtime.EnrichmentRuntime#gate}. The hierarchy is
 * sealed so the interpreter's pattern-match {@code switch} is exhaustive at compile time.
 *
 * <p>Internal nodes ({@link And}, {@link Or}, {@link Not}, {@link Compare}, {@link In},
 * {@link BoolCall}) compose boolean sub-trees. Leaves are {@link Leaf} instances shared between
 * the condition hierarchy and the leaf-producing nodes.
 */
public sealed interface Condition
    permits And, Or, Not, Compare, In, BoolCall {

  /**
   * Evaluates this boolean expression against the given runtime context.
   *
   * @param ctx the advice-context values for this exit event
   * @return {@code true} if the block should enrich; {@code false} if it should be skipped
   */
  boolean eval(EvalContext ctx);
}
