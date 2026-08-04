package org.otel.agent.expr;

import org.otel.agent.runtime.resolver.ValueResolver;

/**
 * Runtime evaluation context passed to every {@link Condition} and {@link Leaf} evaluation.
 *
 * <p>Carries the three advice-context values ({@code receiver}, {@code arguments}, {@code returned})
 * plus the shared {@link ValueResolver} that {@link Property} leaves use to navigate path
 * segments. The resolver is the same instance used by {@link
 * org.otel.agent.runtime.EnrichmentRuntime} for {@code <attribute>} resolution, so expression
 * atoms and attribute paths share {@link org.otel.agent.runtime.accessor.AccessorCache} entries.
 *
 * <p>One {@code EvalContext} is stack-allocated per exit event in {@link
 * org.otel.agent.runtime.EnrichmentRuntime#gate} and passed by reference through the AST walk —
 * no per-node allocation.
 */
public record EvalContext(
    Object receiver,
    Object[] arguments,
    Object returned,
    ValueResolver resolver) {}
