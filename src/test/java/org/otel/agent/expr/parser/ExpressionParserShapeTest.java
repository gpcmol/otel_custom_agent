package org.otel.agent.expr.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.otel.agent.expr.And;
import org.otel.agent.expr.BoolCall;
import org.otel.agent.expr.Compare;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.ExpressionCompileException;
import org.otel.agent.expr.In;
import org.otel.agent.expr.Literal;
import org.otel.agent.expr.Not;
import org.otel.agent.expr.Or;
import org.otel.agent.expr.Property;
import org.otel.agent.expr.SizeCall;

class ExpressionParserShapeTest {

  private final ExpressionParser parser = new ExpressionParser();

  private Condition parse(final String expr) throws ExpressionCompileException {
    return parse(expr, Fixture.class, "process");
  }

  private Condition parse(final String expr, final Class<?> cls, final String methodName)
      throws ExpressionCompileException {
    final Method method;
    try {
      method = cls.getMethod(methodName);
    } catch (final NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
    return parser.parse(expr, cls.getName() + "#" + methodName, cls, method);
  }

  // --- Basic comparisons ---

  @Test
  void equalityProducesCompare() throws ExpressionCompileException {
    final Condition c = parse("$this.brand == 'BMW'");
    assertInstanceOf(Compare.class, c);
    final Compare cmp = (Compare) c;
    assertInstanceOf(Property.class, cmp.left());
    assertInstanceOf(Literal.class, cmp.right());
    assertEquals(Compare.Op.EQ, cmp.op());
  }

  @Test
  void notEqualProducesCompare() throws ExpressionCompileException {
    final Condition c = parse("$this.brand != 'BMW'");
    assertInstanceOf(Compare.class, c);
    assertEquals(Compare.Op.NE, ((Compare) c).op());
  }

  @Test
  void greaterThanProducesCompare() throws ExpressionCompileException {
    final Condition c = parse("$this.mileage > 1000");
    assertInstanceOf(Compare.class, c);
    assertEquals(Compare.Op.GT, ((Compare) c).op());
  }

  // --- Boolean operators ---

  @Test
  void andProducesAndNode() throws ExpressionCompileException {
    final Condition c = parse("$this.brand == 'a' && $this.brand == 'b'");
    assertInstanceOf(And.class, c);
    assertEquals(2, ((And) c).terms().size());
  }

  @Test
  void orProducesOrNode() throws ExpressionCompileException {
    final Condition c = parse("$this.brand == 'a' || $this.brand == 'b'");
    assertInstanceOf(Or.class, c);
    assertEquals(2, ((Or) c).terms().size());
  }

  @Test
  void notProducesNotNode() throws ExpressionCompileException {
    final Condition c = parse("!($this.brand == 'a')");
    assertInstanceOf(Not.class, c);
  }

  // --- Precedence ---

  @Test
  void andHasHigherPrecedenceThanOr() throws ExpressionCompileException {
    // a || b && c parses as a || (b && c)
    final Condition c = parse("$this.brand == 'a' || $this.brand == 'b' && $this.brand == 'c'");
    assertInstanceOf(Or.class, c);
    final Or or = (Or) c;
    assertEquals(2, or.terms().size());
    assertInstanceOf(Compare.class, or.terms().get(0));
    assertInstanceOf(And.class, or.terms().get(1));
  }

  @Test
  void parenthesesOverridePrecedence() throws ExpressionCompileException {
    // (a || b) && c parses as (a || b) && c
    final Condition c = parse("($this.brand == 'a' || $this.brand == 'b') && $this.brand == 'c'");
    assertInstanceOf(And.class, c);
    final And and = (And) c;
    assertEquals(2, and.terms().size());
    assertInstanceOf(Or.class, and.terms().get(0));
    assertInstanceOf(Compare.class, and.terms().get(1));
  }

  @Test
  void arbitraryNesting() throws ExpressionCompileException {
    // A && (B || C) && !D
    final Condition c = parse(
        "$this.brand == 'a' && ($this.brand == 'b' || $this.brand == 'c') && !($this.brand == 'd')");
    assertInstanceOf(And.class, c);
    final And and = (And) c;
    assertEquals(3, and.terms().size());
    assertInstanceOf(Compare.class, and.terms().get(0));
    assertInstanceOf(Or.class, and.terms().get(1));
    assertInstanceOf(Not.class, and.terms().get(2));
  }

  // --- in operator ---

  @Test
  void inOperatorProducesInNode() throws ExpressionCompileException {
    final Condition c = parse("$this.mileage in [0, 100000]");
    assertInstanceOf(In.class, c);
    final In in = (In) c;
    assertInstanceOf(Property.class, in.value());
    assertEquals(2, in.literals().size());
  }

  // --- Function calls ---

  @Test
  void sizeProducesSizeCall() throws ExpressionCompileException {
    final Condition c = parse("size($this.orders) > 5");
    assertInstanceOf(Compare.class, c);
    final Compare cmp = (Compare) c;
    assertInstanceOf(SizeCall.class, cmp.left());
  }

  @Test
  void containsProducesBoolCall() throws ExpressionCompileException {
    final Condition c = parse("contains($this.tags, 'vip')");
    assertInstanceOf(BoolCall.class, c);
    final BoolCall bc = (BoolCall) c;
    assertEquals(BoolCall.Fn.CONTAINS_COLLECTION, bc.fn());
  }

  @Test
  void likeProducesBoolCall() throws ExpressionCompileException {
    final Condition c = parse("like($this.brand, 'BMW%')");
    assertInstanceOf(BoolCall.class, c);
    assertEquals(BoolCall.Fn.LIKE, ((BoolCall) c).fn());
  }

  @Test
  void ilikeProducesBoolCall() throws ExpressionCompileException {
    final Condition c = parse("ilike($this.brand, 'bmw%')");
    assertInstanceOf(BoolCall.class, c);
    assertEquals(BoolCall.Fn.ILIKE, ((BoolCall) c).fn());
  }

  @Test
  void icontainsProducesBoolCall() throws ExpressionCompileException {
    final Condition c = parse("icontains($this.tags, 'vip')");
    assertInstanceOf(BoolCall.class, c);
    assertEquals(BoolCall.Fn.ICONTAINS_COLLECTION, ((BoolCall) c).fn());
  }

  // --- Literals ---

  @Test
  void nullLiteralProducesNullTypedLiteral() throws ExpressionCompileException {
    final Condition c = parse("$this.brand == null");
    assertInstanceOf(Compare.class, c);
    final Compare cmp = (Compare) c;
    assertInstanceOf(Literal.class, cmp.right());
    assertTrue(((Literal) cmp.right()).value() == null);
  }

  @Test
  void booleanTrueLiteral() throws ExpressionCompileException {
    final Condition c = parse("$this.electric == true");
    assertInstanceOf(Compare.class, c);
  }

  // --- $argN and $return roots ---

  @Test
  void argRootInComparison() throws ExpressionCompileException {
    final Condition c = parseWithArgs("$arg0.brand == 'BMW'");
    assertInstanceOf(Compare.class, c);
    final Compare cmp = (Compare) c;
    assertInstanceOf(Property.class, cmp.left());
  }

  private Condition parseWithArgs(final String expr) throws ExpressionCompileException {
    try {
      final Method m = ArgFixture.class.getMethod("park", Car.class);
      return parser.parse(expr, "ArgFixture#park", ArgFixture.class, m);
    } catch (final NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void returnRootInComparison() throws ExpressionCompileException {
    final Condition c = parseReturn("$return.status == 'ok'");
    assertInstanceOf(Compare.class, c);
  }

  private Condition parseReturn(final String expr) throws ExpressionCompileException {
    try {
      final Method m = ReturnFixture.class.getMethod("process");
      return parser.parse(expr, "ReturnFixture#process", ReturnFixture.class, m);
    } catch (final NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  // --- Error cases ---

  @Test
  void unknownFunctionThrows() {
    assertThrows(ExpressionCompileException.class, () -> parse("unknownFunc($this.brand)"));
  }

  @Test
  void trailingOperatorThrows() {
    assertThrows(ExpressionCompileException.class, () -> parse("$this.brand == "));
  }

  @Test
  void unclosedParenthesisThrows() {
    assertThrows(ExpressionCompileException.class, () -> parse("($this.brand == 'BMW'"));
  }

  // --- Test fixtures ---

  public static final class Fixture {
    public String brand = "BMW";
    public long mileage = 50_000L;
    public boolean electric = true;
    public java.util.List<String> tags = java.util.List.of("vip");
    public java.util.List<String> orders = java.util.List.of("o1", "o2", "o3");

    public String getBrand() {
      return brand;
    }

    public long getMileage() {
      return mileage;
    }

    public boolean isElectric() {
      return electric;
    }

    public java.util.List<String> getTags() {
      return tags;
    }

    public java.util.List<String> getOrders() {
      return orders;
    }

    public void process() {}
  }

  public static final class Car {
    public String brand = "BMW";

    public String getBrand() {
      return brand;
    }
  }

  public static final class ArgFixture {
    public void park(final Car car) {}
  }

  public static final class ReturnFixture {
    public Result process() {
      return new Result();
    }
  }

  public static final class Result {
    public String status = "ok";

    public String getStatus() {
      return status;
    }
  }
}