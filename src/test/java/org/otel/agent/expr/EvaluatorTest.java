package org.otel.agent.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.resolver.ValueResolver;

class EvaluatorTest {

  private static EvalContext ctx() {
    return new EvalContext(null, new Object[0], null, null);
  }

  private static EvalContext ctx(final Object receiver) {
    return new EvalContext(receiver, new Object[0], null, new ValueResolver(new AccessorCache()));
  }

  private static Condition eqTrue(final boolean leftVal) {
    return new Compare(
        Compare.Op.EQ,
        new Literal(leftVal, Boolean.class),
        new Literal(true, Boolean.class),
        Boolean.class);
  }

  @Test
  void literalEqualsLiteral() {
    assertTrue(new Compare(Compare.Op.EQ, new Literal("BMW", String.class), new Literal("BMW", String.class), String.class).eval(ctx()));
  }

  @Test
  void literalNotEqualsLiteral() {
    assertTrue(new Compare(Compare.Op.NE, new Literal("BMW", String.class), new Literal("Audi", String.class), String.class).eval(ctx()));
  }

  @Test
  void longComparison() {
    assertTrue(new Compare(Compare.Op.GT, new Literal(10L, Long.class), new Literal(5L, Long.class), Long.class).eval(ctx()));
    assertTrue(new Compare(Compare.Op.LT, new Literal(5L, Long.class), new Literal(10L, Long.class), Long.class).eval(ctx()));
    assertTrue(new Compare(Compare.Op.LE, new Literal(5L, Long.class), new Literal(5L, Long.class), Long.class).eval(ctx()));
    assertTrue(new Compare(Compare.Op.GE, new Literal(5L, Long.class), new Literal(5L, Long.class), Long.class).eval(ctx()));
  }

  @Test
  void doubleComparison() {
    assertTrue(new Compare(Compare.Op.GT, new Literal(10.5, Double.class), new Literal(5.5, Double.class), Double.class).eval(ctx()));
  }

  @Test
  void booleanComparison() {
    assertTrue(new Compare(Compare.Op.EQ, new Literal(true, Boolean.class), new Literal(true, Boolean.class), Boolean.class).eval(ctx()));
    assertTrue(new Compare(Compare.Op.NE, new Literal(true, Boolean.class), new Literal(false, Boolean.class), Boolean.class).eval(ctx()));
  }

  @Test
  void inOperator() {
    final Condition c = new In(
        new Literal(42L, Long.class),
        List.of(new Literal(0L, Long.class), new Literal(42L, Long.class), new Literal(100L, Long.class)),
        Long.class);
    assertTrue(c.eval(ctx()));
  }

  @Test
  void inOperatorNoMatch() {
    final Condition c = new In(
        new Literal(999L, Long.class),
        List.of(new Literal(0L, Long.class), new Literal(42L, Long.class)),
        Long.class);
    assertFalse(c.eval(ctx()));
  }

  // --- Short-circuit tests using a counter fixture ---

  public static final class Counter {
    private int count = 0;

    public boolean getFlag() {
      count++;
      return true;
    }

    public int count() {
      return count;
    }
  }

  private static Condition flagLeaf() {
    return new Compare(
        Compare.Op.EQ,
        new Property(new RootSource.This(), List.of(new PropertySegment("flag")), Boolean.class),
        new Literal(true, Boolean.class),
        Boolean.class);
  }

  @Test
  void andShortCircuitsOnFalse() {
    final Counter counter = new Counter();
    final Condition and = new And(List.of(eqTrue(false), flagLeaf()));
    assertFalse(and.eval(ctx(counter)));
    assertEquals(0, counter.count(), "right term must NOT be evaluated when left is false");
  }

  @Test
  void orShortCircuitsOnTrue() {
    final Counter counter = new Counter();
    final Condition or = new Or(List.of(eqTrue(true), flagLeaf()));
    assertTrue(or.eval(ctx(counter)));
    assertEquals(0, counter.count(), "right term must NOT be evaluated when left is true");
  }

  @Test
  void notNegatesTrue() {
    assertFalse(new Not(eqTrue(true)).eval(ctx()));
  }

  @Test
  void notNegatesFalse() {
    assertTrue(new Not(eqTrue(false)).eval(ctx()));
  }

  // --- Property leaf evaluation ---

  public static final class Fixture {
    public String brand = "BMW";

    public String getBrand() {
      return brand;
    }

    public long getMileage() {
      return 50_000L;
    }

    public boolean getElectric() {
      return true;
    }
  }

  @Test
  void propertyLeafResolvesViaValueResolver() {
    final Property brandLeaf = new Property(
        new RootSource.This(),
        List.of(new PropertySegment("brand")),
        String.class);
    final Condition c = new Compare(Compare.Op.EQ, brandLeaf, new Literal("BMW", String.class), String.class);
    assertTrue(c.eval(ctx(new Fixture())));
  }

  @Test
  void propertyLeafNumericComparison() {
    final Property mileageLeaf = new Property(
        new RootSource.This(),
        List.of(new PropertySegment("mileage")),
        Long.class);
    final Condition c = new Compare(Compare.Op.GT, mileageLeaf, new Literal(40_000L, Long.class), Long.class);
    assertTrue(c.eval(ctx(new Fixture())));
  }
}