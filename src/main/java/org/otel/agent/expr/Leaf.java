package org.otel.agent.expr;

/**
 * Sealed AST node representing a value-producing expression (an "operand") in the condition DSL.
 *
 * <p>Leaves are the building blocks of comparisons and function calls. Each leaf produces an
 * {@code Object} value when {@link #read} is called with the runtime {@link EvalContext}. The
 * sealed hierarchy permits three implementations:
 *
 * <ul>
 *   <li>{@link Literal} — a compile-time constant (string, number, boolean, {@code null})
 *   <li>{@link Property} — a path-rooted expression ({@code $this.brand},
 *       {@code $arg0.passengers[1].name}) resolved via the shared
 *       {@link org.otel.agent.runtime.resolver.ValueResolver}
 *   <li>{@link Arithmetic} — a binary arithmetic computation ({@code 5 + 3},
 *       {@code size($x) * 2}) producing a numeric value
 * </ul>
 */
public sealed interface Leaf
    permits Literal, Property, Arithmetic, SizeCall {

  /**
   * Reads this leaf's runtime value.
   *
   * @param ctx the advice-context values for this exit event
   * @return the resolved value, or {@code null} if the leaf cannot be resolved
   */
  Object read(EvalContext ctx);
}
