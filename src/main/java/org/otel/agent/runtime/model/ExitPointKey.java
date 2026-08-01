package org.otel.agent.runtime.model;

/**
 * Composite key for {@link ExitPointIndex} lookups: the receiver's runtime class plus the invoked
 * exit-point method name. Both are available from ByteBuddy advice context without reflection.
 */
public record ExitPointKey(Class<?> type, String methodName) {}