package org.otel.agent.config.model;

import java.util.List;
import org.otel.agent.expr.Condition;

/**
 * One declared exit-point method and the {@link ExitRule}s that apply when it exits.
 *
 * <p>Compiled from a {@code <enrich class method>} block. Bound at runtime to
 * {@link org.otel.agent.runtime.model.ExitPointKey} via
 * {@link org.otel.agent.runtime.model.ExitPointIndex#find(Class, String)}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code rootClass} — resolved {@link Class} for {@code <enrich class>}, used for
 *       {@code isAssignableFrom} subclass matching
 *   <li>{@code rootClassName} — dotted name retained for reload diffing and logging
 *   <li>{@code methodName} — declared method name (any parameter arity is instrumented)
 *   <li>{@code rules} — immutable list of {@link ExitRule} resolved at this exit point
 *   <li>{@code condition} — optional compiled DSL expression that gates the block; {@code null}
 *       when no {@code expr} attribute was declared or when a parse failure silently disabled it
 * </ul>
 */
public record ExitPoint(
    Class<?> rootClass,
    String rootClassName,
    String methodName,
    List<ExitRule> rules,
    Condition condition) {
  public ExitPoint {
    rules = List.copyOf(rules);
  }

  /** Backward-compatible constructor — no condition (unconditional enrichment). */
  public ExitPoint(final Class<?> rootClass, final String rootClassName, final String methodName,
      final List<ExitRule> rules) {
    this(rootClass, rootClassName, methodName, rules, null);
  }
}