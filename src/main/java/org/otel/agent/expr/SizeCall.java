package org.otel.agent.expr;

import java.lang.reflect.Array;
import java.util.Collection;

/**
 * The {@code size()} function — returns the number of elements in a collection or array.
 *
 * <p>Produces a {@code Long} value: {@code Collection.size()}, {@code Array.getLength}, or
 * {@code 0L} for {@code null}. Used inside comparisons ({@code size($x) > 5}). The type-checker
 * ensures the operand is a {@code Collection} or array at parse time.
 */
public record SizeCall(Leaf collection) implements Leaf {
  @Override
  public Object read(final EvalContext ctx) {
    final Object c = collection.read(ctx);
    if (c == null) return 0L;
    if (c instanceof Collection<?> col) return (long) col.size();
    if (c.getClass().isArray()) return (long) Array.getLength(c);
    return null;
  }
}