package org.otel.agent.expr;

import java.util.List;

/**
 * Collection-membership test ({@code in}).
 *
 * <p>Checks whether the value produced by {@code value} equals any of the compile-time
 * {@code literals}. The {@code elementType} is determined at parse-time and ensures both sides
 * are the same type — the runtime comparison uses {@code Object.equals}.
 */
public record In(Leaf value, List<Literal> literals, Class<?> elementType) implements Condition {
  public In {
    literals = List.copyOf(literals);
  }

  @Override
  public boolean eval(final EvalContext ctx) {
    final Object v = value.read(ctx);
    if (v == null) return false;
    for (final Literal lit : literals) {
      if (v.equals(lit.value())) return true;
    }
    return false;
  }
}