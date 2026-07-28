package org.otel.agent.runtime.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FifoCacheTest {
  @Test
  void evictsOldestEntry() {
    final FifoCache<String, Integer> cache = new FifoCache<>(2);
    cache.put("a", 1);
    cache.put("b", 2);
    assertEquals(1, cache.get("a"));
    cache.put("c", 3);
    assertNull(cache.get("a"));
    assertEquals(2, cache.get("b"));
    assertEquals(3, cache.get("c"));
  }
}
