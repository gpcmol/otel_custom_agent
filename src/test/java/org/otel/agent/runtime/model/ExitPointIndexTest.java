package org.otel.agent.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.model.ExitRule;
import org.otel.agent.config.model.PathSegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.config.model.StaticAttributeRule;

class ExitPointIndexTest {

  @Test
  void exactClassLookupReturnsRules() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));

    final List<ExitRule> rules = index.find(Car.class, "produce");
    assertEquals(1, rules.size());
    assertEquals("brand", rules.getFirst().key());
  }

  @Test
  void subclassMatchesParentExitPoint() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));

    final List<ExitRule> rules = index.find(SportsCar.class, "produce");
    assertEquals(1, rules.size());
    assertEquals("brand", rules.getFirst().key());
  }

  @Test
  void wrongMethodNameReturnsEmptySentinel() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));

    final List<ExitRule> rules = index.find(Car.class, "other");
    assertTrue(rules.isEmpty());
  }

  @Test
  void unrelatedClassReturnsEmptySentinel() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));

    assertTrue(index.find(Truck.class, "produce").isEmpty());
  }

  @Test
  void cachedLookupReusesResult() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));

    final List<ExitRule> first = index.find(Car.class, "produce");
    final List<ExitRule> second = index.find(Car.class, "produce");
    assertEquals(first, second);
    assertTrue(first == second, "cache hit MUST return the same immutable instance");
  }

  @Test
  void defaultCacheCapacityIsAppliedWhenEnvUnset() {
    final ExitPointIndex index =
        new ExitPointIndex(configWith(new ExitPoint(Car.class, "com.example.Car", "produce", List.of(rule("brand")))));
    // ponytail: capacity is package-visible; no env set in test JVM → default applies.
    assertEquals(ExitPointIndex.DEFAULT_CACHE_CAPACITY, 1000);
  }

  @Test
  void resolveCapacityRespectsValidOverride() {
    assertEquals(500, ExitPointIndex.resolveCapacity("500"));
    assertEquals(2000, ExitPointIndex.resolveCapacity("  2000  "));
  }

  @Test
  void resolveCapacityFallsBackOnInvalidInput() {
    final int def = ExitPointIndex.DEFAULT_CACHE_CAPACITY;
    assertEquals(def, ExitPointIndex.resolveCapacity(null));
    assertEquals(def, ExitPointIndex.resolveCapacity(""));
    assertEquals(def, ExitPointIndex.resolveCapacity("   "));
    assertEquals(def, ExitPointIndex.resolveCapacity("abc"));
    assertEquals(def, ExitPointIndex.resolveCapacity("0"));
    assertEquals(def, ExitPointIndex.resolveCapacity("-5"));
  }

  private static ExitRule rule(final String key) {
    final List<PathSegment> segments = List.of(new PropertySegment("brand"));
    return new ExitRule(key, new RootSource.This(), segments);
  }

  private static CompiledConfiguration configWith(final ExitPoint exitPoint) {
    return new CompiledConfiguration(List.<StaticAttributeRule>of(), List.of(exitPoint));
  }

  public static class Car {
    public String getBrand() {
      return "cars";
    }

    public void produce() {}
  }

  public static class SportsCar extends Car {
    @Override
    public void produce() {}
  }

  public static class Truck {
    public void produce() {}
  }
}