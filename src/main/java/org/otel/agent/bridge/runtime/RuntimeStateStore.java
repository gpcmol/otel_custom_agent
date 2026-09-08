package org.otel.agent.bridge.runtime;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.ExitPoint;

/** Owns the bridge's per-loader state and the global instrumentation matcher data. */
final class RuntimeStateStore {
  private static final RuntimeState DISABLED = RuntimeState.disabled();
  private static final WeakReference<RuntimeState> DISABLED_REF = new WeakReference<>(DISABLED);

  private final Map<ClassLoader, WeakReference<RuntimeState>> states = new WeakHashMap<>();
  private final Map<ClassLoader, Boolean> enabled = new WeakHashMap<>();
  private Set<String> rootNames = Set.of();
  private Map<String, Set<String>> rootMethods = Map.of();
  private String activeXml;

  synchronized void publish(final ClassLoader loader, final RuntimeState state) {
    states.put(loader, new WeakReference<>(state));
    enabled.put(loader, Boolean.TRUE);
    final Map<String, Set<String>> methodsByClass = new HashMap<>();
    for (final ExitPoint exitPoint : state.configuration().exitPoints()) {
      methodsByClass
          .computeIfAbsent(exitPoint.rootClassName(), ignored -> new HashSet<>())
          .add(exitPoint.methodName());
    }
    final Map<String, Set<String>> immutableMethods = new HashMap<>();
    methodsByClass.forEach((name, methods) -> immutableMethods.put(name, Set.copyOf(methods)));
    rootNames = Set.copyOf(immutableMethods.keySet());
    rootMethods = Map.copyOf(immutableMethods);
  }

  synchronized void prepareForInitialization(
      final ClassLoader loader, final Map<String, Set<String>> methodsByClass) {
    if (methodsByClass.isEmpty()) return;
    rootNames = Set.copyOf(methodsByClass.keySet());
    enabled.put(loader, Boolean.TRUE);
    states.putIfAbsent(loader, DISABLED_REF);
  }

  synchronized void disable(final ClassLoader loader) {
    states.put(loader, DISABLED_REF);
    enabled.put(loader, Boolean.FALSE);
  }

  synchronized RuntimeState state(final ClassLoader loader) {
    final WeakReference<RuntimeState> reference = states.get(loader);
    if (reference == null) return DISABLED;
    final RuntimeState state = reference.get();
    return state == null ? DISABLED : state;
  }

  synchronized boolean isEnabled(final ClassLoader loader) {
    return enabled.getOrDefault(loader, Boolean.FALSE);
  }

  synchronized List<ClassLoader> loaders() {
    return List.copyOf(states.keySet());
  }

  synchronized Set<String> rootNames() {
    return rootNames;
  }

  synchronized Map<String, Set<String>> rootMethods() {
    return rootMethods;
  }

  synchronized String activeXml() {
    return activeXml;
  }

  synchronized void setActiveXml(final String xml) {
    activeXml = xml;
  }

  synchronized void reset() {
    states.clear();
    enabled.clear();
    rootNames = Set.of();
    rootMethods = Map.of();
    activeXml = null;
  }
}
