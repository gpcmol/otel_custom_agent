package org.otel.agent.expr;

import java.util.List;

/**
 * Boolean disjunction ({@code ||}) of sub-conditions.
 *
 * <p>Evaluates terms left-to-right with short-circuit: the first {@code true} term makes the
 * whole {@code Or} evaluate to {@code true} without evaluating the remaining terms.
 */
public record Or(List<Condition> terms) implements Condition {
  public Or {
    terms = List.copyOf(terms);
  }

  @Override
  public boolean eval(final EvalContext ctx) {
    for (final Condition term : terms) {
      if (term.eval(ctx)) return true;
    }
    return false;
  }
}