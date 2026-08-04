package org.otel.agent.expr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.resolver.ValueResolver;

class ReturnOnVoidTest {

  private static EvalContext voidCtx() {
    // void method → returned is null
    return new EvalContext(null, new Object[0], null, new ValueResolver(new AccessorCache()));
  }

  @Test
  void returnEqualsNullOnVoid() {
    final Property retLeaf = new Property(
        new RootSource.ReturnValue(),
        List.of(),
        Object.class);
    final Condition c = new Compare(Compare.Op.EQ, retLeaf, new Literal(null, Object.class), Object.class);
    assertTrue(c.eval(voidCtx()), "$return == null must be true on a void method");
  }

  @Test
  void returnNotEqualsNullOnVoid() {
    final Property retLeaf = new Property(
        new RootSource.ReturnValue(),
        List.of(),
        Object.class);
    final Condition c = new Compare(Compare.Op.NE, retLeaf, new Literal(null, Object.class), Object.class);
    assertFalse(c.eval(voidCtx()), "$return != null must be false on a void method");
  }

  @Test
  void returnPropertyOnVoidIsFalse() {
    // $return.foo == 'x' — $return is null, so .foo resolves to null, comparison is false
    final Property retFooLeaf = new Property(
        new RootSource.ReturnValue(),
        List.of(new PropertySegment("foo")),
        Object.class);
    final Condition c = new Compare(Compare.Op.EQ, retFooLeaf, new Literal("x", String.class), String.class);
    assertFalse(c.eval(voidCtx()));
  }

  // --- Non-void method with $return ---

  public static final class Result {
    public String status = "ok";

    public String getStatus() {
      return status;
    }
  }

  @Test
  void returnOnNonVoidMethodUsesReturnValue() {
    final Result result = new Result();
    final Property retStatus = new Property(
        new RootSource.ReturnValue(),
        List.of(new PropertySegment("status")),
        String.class);
    final Condition c = new Compare(Compare.Op.EQ, retStatus, new Literal("ok", String.class), String.class);
    final EvalContext ctx = new EvalContext(null, new Object[0], result, new ValueResolver(new AccessorCache()));
    assertTrue(c.eval(ctx));
  }
}