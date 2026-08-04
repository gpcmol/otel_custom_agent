package org.otel.agent.expr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContainsIcontainsTest {

  private static EvalContext ctx() {
    return new EvalContext(null, new Object[0], null, null);
  }

  // --- contains on String (substring) ---

  @Test
  void stringContainsSubstring() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.CONTAINS_STRING,
        new Literal("the BMW M5", String.class),
        new Literal("BMW", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  void stringContainsNoMatch() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.CONTAINS_STRING,
        new Literal("audi A4", String.class),
        new Literal("BMW", String.class));
    assertFalse(c.eval(ctx()));
  }

  // --- contains on Collection (element equality) ---

  @Test
  @SuppressWarnings("unchecked")
  void collectionContainsElement() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.CONTAINS_COLLECTION,
        new Literal(List.of("vip", "user", "admin"), List.class),
        new Literal("vip", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void collectionDoesNotContainElement() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.CONTAINS_COLLECTION,
        new Literal(List.of("vip", "user"), List.class),
        new Literal("banned", String.class));
    assertFalse(c.eval(ctx()));
  }

  // --- icontains on String (whole-string equalsIgnoreCase) ---

  @Test
  void stringIcontainsWholeString() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ICONTAINS_STRING,
        new Literal("nl", String.class),
        new Literal("NL", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  void stringIcontainsDoesNotSubstringMatch() {
    // ponytail: icontains on String is whole-string equalsIgnoreCase, NOT substring.
    // For case-insensitive substring matching use ilike(hay, "%needle%").
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ICONTAINS_STRING,
        new Literal("the BMW M5", String.class),
        new Literal("Bmw", String.class));
    assertFalse(c.eval(ctx()));
  }

  // --- icontains on Collection<String> (element-wise equalsIgnoreCase) ---

  @Test
  @SuppressWarnings("unchecked")
  void collectionIcontainsElement() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ICONTAINS_COLLECTION,
        new Literal(List.of("VIP", "user"), List.class),
        new Literal("vip", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void collectionIcontainsNoMatch() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ICONTAINS_COLLECTION,
        new Literal(List.of("VIP", "user"), List.class),
        new Literal("banned", String.class));
    assertFalse(c.eval(ctx()));
  }

  // --- null operands ---

  @Test
  void containsWithNullHaystackFalse() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.CONTAINS_STRING,
        new Literal(null, String.class),
        new Literal("x", String.class));
    assertFalse(c.eval(ctx()));
  }

  @Test
  void icontainsWithNullHaystackFalse() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ICONTAINS_STRING,
        new Literal(null, String.class),
        new Literal("x", String.class));
    assertFalse(c.eval(ctx()));
  }

  // --- like / ilike via BoolCall ---

  @Test
  void likeViaBoolCall() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.LIKE,
        new Literal("BMW-X5", String.class),
        new Literal("BMW%", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  void ilikeViaBoolCall() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.ILIKE,
        new Literal("BMW", String.class),
        new Literal("bmw%", String.class));
    assertTrue(c.eval(ctx()));
  }

  @Test
  void likeWithNullValueFalse() {
    final BoolCall c = new BoolCall(
        BoolCall.Fn.LIKE,
        new Literal(null, String.class),
        new Literal("%x%", String.class));
    assertFalse(c.eval(ctx()));
  }
}