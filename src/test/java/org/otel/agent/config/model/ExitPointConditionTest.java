package org.otel.agent.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.expr.And;
import org.otel.agent.expr.Compare;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.Literal;
import org.otel.agent.runtime.model.ExitPointIndex;

class ExitPointConditionTest {

  private static final Condition STUB_CONDITION = new And(List.of(
      new Compare(Compare.Op.EQ, new Literal("BMW", String.class), new Literal("BMW", String.class), String.class)));

  @Test
  void exitPointWithNonNullConditionExposesIt() {
    final ExitPoint ep = new ExitPoint(Fixture.class, "test.Fixture", "process", List.of(), STUB_CONDITION);
    assertEquals(STUB_CONDITION, ep.condition());
  }

  @Test
  void exitPointWithNullConditionDefaultsToNull() {
    final ExitPoint ep = new ExitPoint(Fixture.class, "test.Fixture", "process", List.of(), null);
    assertNull(ep.condition());
  }

  @Test
  void exitPointCanBeBuiltWithoutConditionForBackwardCompat() {
    final ExitPoint ep = new ExitPoint(Fixture.class, "test.Fixture", "process", List.of());
    assertNull(ep.condition());
  }

  @Test
  void findExitPointReturnsExitPointWithCondition() {
    final CompiledConfiguration config = new CompiledConfiguration(
        List.of(),
        List.of(new ExitPoint(Fixture.class, "test.Fixture", "process", List.of(), STUB_CONDITION)));
    final ExitPointIndex index = new ExitPointIndex(config);
    final ExitPoint found = index.findExitPoint(Fixture.class, "process");
    assertNotNull(found);
    assertEquals(STUB_CONDITION, found.condition());
  }

  @Test
  void findExitPointReturnsNullForUnconfiguredMethod() {
    final CompiledConfiguration config = new CompiledConfiguration(
        List.of(),
        List.of(new ExitPoint(Fixture.class, "test.Fixture", "process", List.of(), STUB_CONDITION)));
    final ExitPointIndex index = new ExitPointIndex(config);
    assertNull(index.findExitPoint(Fixture.class, "unknown"));
  }

  public static final class Fixture {
    public void process() {}
  }
}