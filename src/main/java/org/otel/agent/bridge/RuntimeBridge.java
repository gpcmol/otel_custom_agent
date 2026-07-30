package org.otel.agent.bridge;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.RuleIndex;
import org.otel.agent.utils.Base64Util;
import org.otel.agent.webserver.ConfigWebserver;

/**
 * Bridge between the agent extension class loader and application class loaders.
 *
 * <p>ClassLoader architecture:
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │ JVM bootstrap                                               │
 * │  ├─ java.lang, com.sun.net.httpserver, ...                  │
 * │  └─ OpenTelemetry Java agent (javaagent)                    │
 * │     ├─ io.opentelemetry.javaagent.*                         │
 * │     └─ Agent extension class loader                         │
 * │        ├─ org.otel.agent.* (this code)                      │
 * │        ├─ org.otel.agent.bridge.RuntimeBridge  ← this class │
 * │        └─ Instrumentation advice classes                    │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Application class loader                                    │
 * │  ├─ Application classes (instrumented by OTel advice)       │
 * │  └─ org.otel.agent.runtime.EnrichmentRuntime  ← injected    │
 * │     (loaded here so advice can reference it)                │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Configuration flow:
 * <ol>
 *   <li>At startup, {@link #initialize(ClassLoader)} is called from instrumentation advice
 *       (running in the application class loader). It reads the {@code OTEL_CUSTOM_AGENT_CONFIG}
 *       env var, Base64-decodes it, and parses it into a {@link CompiledConfiguration} using
 *       the <em>application</em> class loader (so dynamic rule paths can resolve app classes).</li>
 *   <li>The compiled configuration is published as an immutable {@link RuntimeState} into the
 *       {@code STATES} map, keyed by the application class loader.</li>
 *   <li>At method-exit, the instrumentation advice calls {@link #state(ClassLoader)} to look up
 *       the current {@link RuntimeState} for the receiver's class loader, then calls
 *       {@link org.otel.agent.runtime.EnrichmentRuntime#enrich(Object)} via reflection.</li>
 *   <li>On reload (HTTP POST to the config webserver), {@link #reload(String)} re-parses the
 *       XML for <em>each</em> registered application class loader, publishes a new
 *       {@link RuntimeState}, and forwards the XML to
 *       {@link org.otel.agent.runtime.EnrichmentRuntime#reloadFromBridge(String)} in the
 *       application class loader so the runtime cache is rebuilt there.</li>
 * </ol>
 *
 * <p>Thread safety: all mutations to {@code STATES}, {@code ROOT_NAMES}, and {@code ACTIVE_XML}
 * are guarded by {@code synchronized(RuntimeBridge.class)}. The {@code state(ClassLoader)}
 * lookup uses a single-entry volatile cache ({@code memoLoader}/{@code memoState}) for the
 * common case where advice repeatedly reads state for the same loader.
 */
public final class RuntimeBridge {
  private static final RuntimeState DISABLED = RuntimeState.disabled();
  private static final Map<ClassLoader, RuntimeState> STATES = new WeakHashMap<>();
  private static final AtomicReference<Set<String>> ROOT_NAMES = new AtomicReference<>(Set.of());
  private static final AtomicReference<String> ACTIVE_XML = new AtomicReference<>();
  private static final AtomicBoolean WEBSERVER_STARTED = new AtomicBoolean(false);
  private static volatile ClassLoader memoLoader;
  private static volatile RuntimeState memoState = DISABLED;

  private RuntimeBridge() {}

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration) {
    publishLocked(loader, configuration);
  }

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    publishLocked(loader, configuration);
    ACTIVE_XML.set(xml);
  }

  private static void publishLocked(
      final ClassLoader loader, final CompiledConfiguration configuration) {
    final RuntimeState state = RuntimeState.enabled(configuration, new RuleIndex(configuration));
    STATES.put(loader, state);
    memoLoader = loader;
    memoState = state;
    ROOT_NAMES.set(
        configuration.dynamicRules().stream()
            .map(DynamicAttributeRule::rootClassName)
            .collect(Collectors.toUnmodifiableSet()));
  }

  public static void initialize(final ClassLoader applicationLoader) {
    synchronized (RuntimeBridge.class) {
      if (STATES.containsKey(applicationLoader)) return;
    }
    final String encoded = System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    if (encoded == null) {
      synchronized (RuntimeBridge.class) {
        STATES.putIfAbsent(applicationLoader, DISABLED);
      }
      return;
    }
    startWebserverIfNeeded();
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encoded, applicationLoader);
      final String xml = decodeToXml(encoded);
      publish(applicationLoader, configuration, xml);
      logConfig(configuration);
    } catch (final ConfigurationException exception) {
      synchronized (RuntimeBridge.class) {
        STATES.putIfAbsent(applicationLoader, DISABLED);
      }
    }
  }

  public static ReloadResult reload(final String xml) {
    final List<ClassLoader> loaders;
    synchronized (RuntimeBridge.class) {
      loaders = new ArrayList<>(STATES.keySet());
    }
    final List<String> failures = new ArrayList<>();
    int updated = 0;
    int staticCount = 0;
    int dynamicCount = 0;

    for (final ClassLoader loader : loaders) {
      try {
        final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
        if (!forwardReloadToApplicationClassloader(xml, loader)) {
          failures.add("reload failed in application classloader");
          continue;
        }
        publish(loader, configuration, xml);
        updated++;
        staticCount = configuration.staticRules().size();
        dynamicCount = configuration.dynamicRules().size();
      } catch (final ConfigurationException exception) {
        failures.add(category(exception));
      }
    }

    return new ReloadResult(updated, staticCount, dynamicCount, failures);
  }

  private static String category(final ConfigurationException exception) {
    final String message = exception.getMessage();
    final int colon = message.indexOf(':');
    return colon < 0 ? message : message.substring(0, colon);
  }

  public static String activeXml() {
    return ACTIVE_XML.get();
  }

  private static void startWebserverIfNeeded() {
    if (!WEBSERVER_STARTED.compareAndSet(false, true)) return;
    try {
      ConfigWebserver.start();
    } catch (final Exception exception) {
      System.getLogger(RuntimeBridge.class.getName())
          .log(System.Logger.Level.WARNING, "config webserver failed to start", exception);
    }
  }

  private static boolean forwardReloadToApplicationClassloader(
      final String xml, final ClassLoader loader) {
    try {
      final Class<?> enrichmentRuntime =
          Class.forName("org.otel.agent.runtime.EnrichmentRuntime", true, loader);
      enrichmentRuntime.getMethod("reloadFromBridge", String.class).invoke(null, xml);
      return true;
    } catch (final InvocationTargetException reloadFailure) {
      return false;
    } catch (final ReflectiveOperationException notInjected) {
      // The runtime is not loaded in this classloader, so there is nothing to update there.
      return true;
    }
  }

  private static String decodeToXml(final String encoded) {
    try {
      return new String(Base64Util.decode(encoded), StandardCharsets.UTF_8);
    } catch (final ConfigurationException exception) {
      return null;
    }
  }

  private static void logConfig(final CompiledConfiguration configuration) {
    System.getLogger(RuntimeBridge.class.getName())
        .log(
            System.Logger.Level.INFO,
            "enabled static={0} dynamic={1} roots={2}",
            configuration.staticRules().size(),
            configuration.dynamicRules().size(),
            configuration.dynamicRules().stream()
                .map(DynamicAttributeRule::rootClass)
                .distinct()
                .count());
  }

  public static RuntimeState state(final ClassLoader loader) {
    if (loader != null && loader == memoLoader) return memoState;
    synchronized (RuntimeBridge.class) {
      final RuntimeState state = STATES.getOrDefault(loader, DISABLED);
      memoLoader = loader;
      memoState = state;
      return state;
    }
  }

  public static Set<String> rootClassNames() {
    return ROOT_NAMES.get();
  }

  public static void resetForTesting() {
    ConfigWebserver.stop();
    WEBSERVER_STARTED.set(false);
    synchronized (RuntimeBridge.class) {
      STATES.clear();
    }
    memoLoader = null;
    memoState = DISABLED;
    ROOT_NAMES.set(Set.of());
    ACTIVE_XML.set(null);
  }
}
