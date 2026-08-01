package org.otel.agent.config.model;

import java.util.List;

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
 * </ul>
 */
public record ExitPoint(
    Class<?> rootClass, String rootClassName, String methodName, List<ExitRule> rules) {
  public ExitPoint {
    rules = List.copyOf(rules);
  }
}