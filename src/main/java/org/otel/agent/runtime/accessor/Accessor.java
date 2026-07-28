package org.otel.agent.runtime.accessor;

@FunctionalInterface
public interface Accessor {
  Object read(Object target);
}
