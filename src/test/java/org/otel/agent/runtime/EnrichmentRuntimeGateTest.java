package org.otel.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.model.ExitRule;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.expr.Compare;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.Literal;
import org.otel.agent.expr.Property;

class EnrichmentRuntimeGateTest {

  public static final class Fixture {
    public String brand = "BMW";

    public String getBrand() {
      return brand;
    }
  }

  private ExitPoint exitPointWith(final Condition condition) {
    return new ExitPoint(
        Fixture.class,
        "test.Fixture",
        "process",
        List.of(new ExitRule("brand", new RootSource.This(), List.of(new PropertySegment("brand")))),
        condition);
  }

  private static Condition brandEquals(final String value) {
    return new Compare(
        Compare.Op.EQ,
        new Property(new RootSource.This(), List.of(new PropertySegment("brand")), String.class),
        new Literal(value, String.class),
        String.class);
  }

  @Test
  void nullExitPointReturnsTrue() {
    assertTrue(EnrichmentRuntime.gate(null, new Fixture(), new Object[0], null));
  }

  @Test
  void nullConditionReturnsTrue() {
    final ExitPoint ep = exitPointWith(null);
    assertTrue(EnrichmentRuntime.gate(ep, new Fixture(), new Object[0], null));
  }

  @Test
  void trueConditionReturnsTrue() {
    final ExitPoint ep = exitPointWith(brandEquals("BMW"));
    assertTrue(EnrichmentRuntime.gate(ep, new Fixture(), new Object[0], null));
  }

  @Test
  void falseConditionReturnsFalse() {
    final ExitPoint ep = exitPointWith(brandEquals("Audi"));
    assertFalse(EnrichmentRuntime.gate(ep, new Fixture(), new Object[0], null));
  }

  @Test
  void returnOnVoidEvaluatesToFalse() {
    final Condition returnCheck = new Compare(
        Compare.Op.EQ,
        new Property(new RootSource.ReturnValue(), List.of(new PropertySegment("brand")), String.class),
        new Literal("x", String.class),
        String.class);
    final ExitPoint ep = exitPointWith(returnCheck);
    assertFalse(EnrichmentRuntime.gate(ep, new Fixture(), new Object[0], null));
  }

  @Test
  void returnEqualsNullOnVoidEvaluatesToTrue() {
    final Condition returnCheck = new Compare(
        Compare.Op.EQ,
        new Property(new RootSource.ReturnValue(), List.of(), Object.class),
        new Literal(null, Object.class),
        Object.class);
    final ExitPoint ep = exitPointWith(returnCheck);
    assertTrue(EnrichmentRuntime.gate(ep, new Fixture(), new Object[0], null));
  }
}