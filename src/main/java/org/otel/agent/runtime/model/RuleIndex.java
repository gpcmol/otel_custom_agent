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
    final List<DynamicAttributeRule> result =
        roots.entrySet().stream()
            .filter(entry -> entry.getKey().isAssignableFrom(runtimeType))
            .flatMap(entry -> entry.getValue().stream())
            .toList();
    applicable.put(runtimeType, result);
    return result;
  }
}
