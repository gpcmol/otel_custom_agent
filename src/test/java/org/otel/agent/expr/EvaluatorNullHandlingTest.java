package org.otel.agent.expr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.resolver.ValueResolver;

class EvaluatorNullHandlingTest {

  private static EvalContext ctx() {
    return new EvalContext(null, new Object[0], null, null);
  }

  private static EvalContext ctx(final Object receiver) {
    return new EvalContext(receiver, new Object[0], null, new ValueResolver(new AccessorCache()));
  }

  @Test
  void nullEqualsNull() {
    assertTrue(new Compare(Compare.Op.EQ, new Literal(null, Object.class), new Literal(null, Object.class), Object.class).eval(ctx()));
  }

  @Test
  void nullNotEqualsNull() {
    assertFalse(new Compare(Compare.Op.NE, new Literal(null, Object.class), new Literal(null, Object.class), Object.class).eval(ctx()));
  }

  @Test
  void leafEqualsNullWhenLeafIsNull() {
    final Property nullProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    assertTrue(new Compare(Compare.Op.EQ, nullProp, new Literal(null, Object.class), Object.class).eval(ctx()));
  }

  @Test
  void leafNotEqualsNullWhenLeafIsNonNull() {
    final Property brandProp = new Property(new RootSource.This(), List.of(new PropertySegment("brand")), String.class);
    final Condition c = new Compare(Compare.Op.NE, brandProp, new Literal(null, Object.class), Object.class);
    assertTrue(c.eval(ctx(new Fixture())));
  }

  @Test
  void comparisonWithNullLeftIsFalse() {
    final Property nullProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    assertFalse(new Compare(Compare.Op.EQ, nullProp, new Literal("BMW", String.class), String.class).eval(ctx(new Fixture())));
  }

  @Test
  void notEqualsWithNullLeftIsFalse() {
    final Property nullProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    assertFalse(new Compare(Compare.Op.NE, nullProp, new Literal("BMW", String.class), String.class).eval(ctx(new Fixture())));
  }

  @Test
  void numericComparisonWithNullIsFalse() {
    final Property nullProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    assertFalse(new Compare(Compare.Op.GT, nullProp, new Literal(5L, Long.class), Long.class).eval(ctx(new Fixture())));
  }

  @Test
  void andTreatsNullBooleanAsFalse() {
    // $arg0.electric and true — when electric is null (doesn't exist), left is false
    final Property missingProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    final Condition c = new Compare(Compare.Op.EQ, missingProp, new Literal(true, Boolean.class), Boolean.class);
    assertFalse(new And(List.of(c, new Compare(Compare.Op.EQ, new Literal(true, Boolean.class), new Literal(true, Boolean.class), Boolean.class))).eval(ctx()));
  }

  @Test
  void notTreatsNullAsTrue() {
    // not $arg0.electric — null operand is treated as false for not, so not false == true
    final Property nullProp = new Property(new RootSource.This(), List.of(new PropertySegment("nonexistent")), Object.class);
    final Condition c = new Compare(Compare.Op.EQ, nullProp, new Literal(true, Boolean.class), Boolean.class);
    assertTrue(new Not(c).eval(ctx(new Fixture())));
  }

  public static final class Fixture {
    public String brand = "BMW";

    public String getBrand() {
      return brand;
    }
  }
}