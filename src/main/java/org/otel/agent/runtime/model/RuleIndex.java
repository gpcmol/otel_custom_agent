package org.otel.agent.runtime.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.runtime.cache.FifoCache;

public final class RuleIndex {
  private final Map<Class<?>, List<DynamicAttributeRule>> roots;
  private final FifoCache<Class<?>, List<DynamicAttributeRule>> applicable = new FifoCache<>(1000);

  public RuleIndex(final CompiledConfiguration configuration) {
    final Map<Class<?>, List<DynamicAttributeRule>> mutable = new HashMap<>();
    for (final DynamicAttributeRule rule : configuration.dynamicRules()) {
      mutable.computeIfAbsent(rule.rootClass(), ignored -> new ArrayList<>()).add(rule);
    }
    final Map<Class<?>, List<DynamicAttributeRule>> immutable = new HashMap<>();
    mutable.forEach((type, rules) -> immutable.put(type, List.copyOf(rules)));
    roots = Collections.unmodifiableMap(immutable);
  }

  public List<DynamicAttributeRule> applicable(final Class<?> runtimeType) {
    final List<DynamicAttributeRule> cached = applicable.get(runtimeType);
    if (cached != null) return cached;
    final List<DynamicAttributeRule> result = new ArrayList<>();
    for (final Map.Entry<Class<?>, List<DynamicAttributeRule>> entry : roots.entrySet()) {
      if (entry.getKey().isAssignableFrom(runtimeType)) {
        result.addAll(entry.getValue());
      }
    }
    final List<DynamicAttributeRule> immutable = List.copyOf(result);
    applicable.put(runtimeType, immutable);
    return immutable;
  }
}
