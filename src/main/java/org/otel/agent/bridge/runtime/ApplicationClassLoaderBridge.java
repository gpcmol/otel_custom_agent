package org.otel.agent.bridge.runtime;

import java.lang.reflect.InvocationTargetException;
import org.otel.agent.bridge.RuntimeState;

/** Handles reflection across the agent extension and application classloader boundary. */
final class ApplicationClassLoaderBridge {
  boolean reload(final String xml, final ClassLoader loader) {
    try {
      final Class<?> runtime = runtimeClass(loader);
      runtime.getMethod("reloadFromBridge", String.class).invoke(null, xml);
      return true;
    } catch (final InvocationTargetException reloadFailure) {
      return false;
    } catch (final ReflectiveOperationException notInjected) {
      return true;
    }
  }

  void pushState(final ClassLoader loader, final RuntimeState state) {
    try {
      runtimeClass(loader).getMethod("setState", RuntimeState.class).invoke(null, state);
    } catch (final InvocationTargetException setStateFailure) {
      System.getLogger(ApplicationClassLoaderBridge.class.getName())
          .log(System.Logger.Level.WARNING, "state strong-push failed", setStateFailure.getCause());
    } catch (final ReflectiveOperationException notInjected) {
      // EnrichmentRuntime adopts the bridge state on its first enrichment call.
    }
  }

  private static Class<?> runtimeClass(final ClassLoader loader) throws ClassNotFoundException {
    return Class.forName("org.otel.agent.runtime.EnrichmentRuntime", true, loader);
  }
}
