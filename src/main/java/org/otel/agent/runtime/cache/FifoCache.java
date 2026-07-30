package org.otel.agent.runtime.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded FIFO cache with lock-free reads. Steady-state {@link #get(Object)} is a pure
 * ConcurrentHashMap lookup; eviction happens only on the write path and is best-effort under
 * concurrency (the bound stays within {@code capacity + small slack}). Spec: lock-free reads
 * preferred, only a short safe initialization path outside steady-state reads.
 */
public final class FifoCache<K, V> {
  private final int capacity;
  private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<K> order = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  public FifoCache(final int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
    this.capacity = capacity;
  }

  public V get(final K key) {
    return values.get(key);
  }

  public void put(final K key, final V value) {
    if (values.putIfAbsent(key, value) != null) return;
    order.add(key);
    if (size.incrementAndGet() > capacity) {
      size.decrementAndGet();
      final K evicted = order.poll();
      if (evicted != null) values.remove(evicted);
    }
  }
}
