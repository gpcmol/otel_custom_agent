package org.otel.agent.config.model;

/**
 * Anchor from which an {@link ExitRule}'s path resolves at an exit-point method. One of the
 * receiver ({@link #THIS}), a method parameter ({@link #ARGUMENT}), or the return value
 * ({@link #RETURN_VALUE}).
 *
 * <p>Sealed so the {@code EnrichmentRuntime} root-selection {@code switch} exhaustively covers
 * the three cases at compile time and the JIT compiles it to a small branch table.
 *
 * <p>Config prefix syntax: {@code $this}, {@code $arg0..$argN} (decimal, 0..127), {@code $return}.
 */
public sealed interface RootSource
    permits RootSource.This, RootSource.Argument, RootSource.ReturnValue {
  /** Resolves from {@code @Advice.This} — the receiver of the exit-point method. */
  record This() implements RootSource {}

  /** Resolves from {@code @Advice.AllArguments[index]} — a single method parameter. */
  record Argument(int index) implements RootSource {}

  /** Resolves from {@code @Advice.Return} — the method return value (null on {@code void}). */
  record ReturnValue() implements RootSource {}
}