package org.otel.agent.runtime.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.model.ExitRule;
import org.otel.agent.runtime.cache.FifoCache;

/**
 * Index of {@link ExitRule}s keyed by {@link ExitPointKey}, enabling efficient lookup of the
 * rules that apply when a given exit-point method exits.
 *
 * <p>Built once per {@link CompiledConfiguration}. The compiled immutable {@link ExitPoint}s
 * are held in a list; the {@code (Class, methodName)} lookup uses a bounded {@link FifoCache}
 * (capacity 1000) so that repeated enrichment for the same {@code (Class, methodName)} pair —
 * the common case — is O(1). On cache miss the index walks all configured {@link ExitPoint}s
 * and selects those whose {@code rootClass.isAssignableFrom(actualClass)} and whose
 * {@code methodName} equals the invoked one; the immutable result (possibly an empty-list
 * sentinel) is cached.
 *
 * <p>Replaces the previous {@code RuleIndex} of the per-instance-method model. Lookup is now by
 * {@code (Class, methodName)} because instrumentation is installed on a single declared method
 * per {@code <enrich>} block, not on every instance method of the configured root class.
 *
 * <p>Thread safety: the underlying {@link FifoCache} is backed by
 * {@link ConcurrentHashMap}, so cache hits are safe to read concurrently from instrumentation
 * advice. The compiled {@link ExitPoint}s are immutable after construction.
 */
public final class ExitPointIndex {
  private static final List<ExitRule> EMPTY = List.of();

  private final List<ExitPoint> exitPoints;
  private final FifoCache<ExitPointKey, List<ExitRule>> cache = new FifoCache<>(1000);

  public ExitPointIndex(final CompiledConfiguration configuration) {
    this.exitPoints = configuration.exitPoints();
  }

  public List<ExitRule> find(final Class<?> runtimeType, final String methodName) {
    final ExitPointKey key = new ExitPointKey(runtimeType, methodName);
    final List<ExitRule> cached = cache.get(key);
    if (cached != null) return cached;
    final List<ExitRule> result = new ArrayList<>();
    for (final ExitPoint exitPoint : exitPoints) {
      if (exitPoint.methodName().equals(methodName)
          && exitPoint.rootClass().isAssignableFrom(runtimeType)) {
        result.addAll(exitPoint.rules());
      }
    }
    final List<ExitRule> immutable = result.isEmpty() ? EMPTY : List.copyOf(result);
    cache.put(key, immutable);
    return immutable;
  }
}