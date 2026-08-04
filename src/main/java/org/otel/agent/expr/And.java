package org.otel.agent.expr;

import java.util.List;

/**
 * Boolean conjunction ({@code &&}) of sub-conditions.
 *
 * <p>Evaluates terms left-to-right with short-circuit: the first {@code false} term makes the
 * whole {@code And} evaluate to {@code false} without evaluating the remaining terms.
 */
public record And(List<Condition> terms) implements Condition {
  public And {
    terms = List.copyOf(terms);
  }

  @Override
  public boolean eval(final EvalContext ctx) {
    for (final Condition term : terms) {
      if (!term.eval(ctx)) return false;
    }
    return true;
  }
}