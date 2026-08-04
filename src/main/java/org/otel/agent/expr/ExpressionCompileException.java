package org.otel.agent.expr;

/**
 * Thrown by {@link org.otel.agent.expr.parser.ExpressionParser} when an {@code expr} attribute
 * cannot be parsed, type-checked, or compiled into a {@link Condition} AST.
 *
 * <p>Carries two pieces of diagnostic information used by {@link
 * org.otel.agent.config.parser.ConfigurationParser} to emit a single startup warning per failed
 * block:
 *
 * <ul>
 *   <li>{@code blockId} — the {@code class#method} identifier from the enclosing {@code <enrich>}
 *       block
 *   <li>{@code category} — a short failure category string (e.g. "syntax error", "type mismatch",
 *       "unknown function")
 * </ul>
 *
 * <p>The caller catches this exception, logs one {@code WARNING}-level message naming the block
 * and category, and attaches a {@code null} {@link Condition} to the {@link
 * org.otel.agent.config.model.ExitPoint} — the block enriches unconditionally (as if {@code expr}
 * were absent).
 */
public final class ExpressionCompileException extends Exception {
  private final String blockId;
  private final String category;

  public ExpressionCompileException(final String blockId, final String category, final String detail) {
    super(blockId + ": " + category + " — " + detail);
    this.blockId = blockId;
    this.category = category;
  }

  public ExpressionCompileException(
      final String blockId, final String category, final String detail, final Throwable cause) {
    super(blockId + ": " + category + " — " + detail, cause);
    this.blockId = blockId;
    this.category = category;
  }

  public String blockId() {
    return blockId;
  }

  public String category() {
    return category;
  }
}