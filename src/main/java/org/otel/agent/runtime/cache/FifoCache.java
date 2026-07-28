package org.otel.agent.runtime.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FifoCache<K, V> {
  private final int capacity;
  private final Map<K, V> values;

  public FifoCache(final int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
    this.values = new LinkedHashMap<>(capacity, 0.75f, false);
  }

  public synchronized V get(final K key) {
    return values.get(key);
  }

  public synchronized void put(final K key, final V value) {
    values.put(key, value);
    if (values.size() > capacity) values.remove(values.keySet().iterator().next());
  }
}
