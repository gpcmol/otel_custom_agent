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

class SizeFunctionTest {

  private static EvalContext ctx() {
    return new EvalContext(null, new Object[0], null, null);
  }

  private static EvalContext ctx(final Object receiver) {
    return new EvalContext(receiver, new Object[0], null, new ValueResolver(new AccessorCache()));
  }

  @Test
  void sizeOfCollection() {
    final SizeCall size = new SizeCall(new Literal(List.of(1, 2, 3), List.class));
    assertEquals(3L, size.read(ctx()));
  }

  @Test
  void sizeOfArray() {
    final SizeCall size = new SizeCall(new Literal(new int[] {1, 2}, int[].class));
    assertEquals(2L, size.read(ctx()));
  }

  @Test
  void sizeOfEmptyCollection() {
    final SizeCall size = new SizeCall(new Literal(List.of(), List.class));
    assertEquals(0L, size.read(ctx()));
  }

  @Test
  void sizeOfNullReturnsZero() {
    final SizeCall size = new SizeCall(new Literal(null, List.class));
    assertEquals(0L, size.read(ctx()));
  }

  @Test
  void sizeInComparisonGreaterThan() {
    final SizeCall size = new SizeCall(new Literal(List.of(1, 2, 3, 4, 5, 6), List.class));
    final Condition c = new Compare(Compare.Op.GT, size, new Literal(5L, Long.class), Long.class);
    assertTrue(c.eval(ctx()));
  }

  @Test
  void sizeInComparisonNotGreaterThan() {
    final SizeCall size = new SizeCall(new Literal(List.of(1, 2, 3), List.class));
    final Condition c = new Compare(Compare.Op.GT, size, new Literal(5L, Long.class), Long.class);
    assertFalse(c.eval(ctx()));
  }

  @Test
  void sizeInBooleanAndExpression() {
    final SizeCall size = new SizeCall(new Literal(List.of("a", "b", "c"), List.class));
    final Condition c = new And(List.of(
        new Compare(Compare.Op.GT, size, new Literal(0L, Long.class), Long.class),
        new Compare(Compare.Op.EQ, new Literal("BMW", String.class), new Literal("BMW", String.class), String.class)));
    assertTrue(c.eval(ctx()));
  }

  public static final class Fixture {
    public List<String> orders = List.of("o1", "o2", "o3", "o4", "o5", "o6");

    public List<String> getOrders() {
      return orders;
    }
  }

  @Test
  void sizeOfPropertyLeaf() {
    final SizeCall size = new SizeCall(new Property(
        new RootSource.This(),
        List.of(new PropertySegment("orders")),
        List.class));
    final Condition c = new Compare(Compare.Op.GT, size, new Literal(5L, Long.class), Long.class);
    assertTrue(c.eval(ctx(new Fixture())));
  }
}