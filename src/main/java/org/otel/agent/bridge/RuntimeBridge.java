package org.otel.agent.bridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.ExitPointIndex;
import org.otel.agent.utils.Base64Util;
import org.otel.agent.webserver.ConfigWebserver;
import org.w3c.dom.Element;

/**
 * Bridge between the agent extension class loader and application class loaders.
 *
 * <p>ClassLoader architecture:
 *
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
 *
 * <ol>
 *   <li>At startup, {@link #initialize(ClassLoader)} is called from the OTel agent's
 *       classLoaderMatcher (running in the agent extension class loader). It reads the {@code
 *       OTEL_CUSTOM_AGENT_CONFIG} env var, Base64-decodes it, and parses it into a {@link
 *       CompiledConfiguration} using the <em>application</em> class loader (so dynamic rule paths
 *       can resolve app classes).
 *   <li>The compiled configuration is published as an immutable {@link RuntimeState}: the bridge
 *       keeps a <em>weak</em> reference (keyed by the application class loader), and also reflects
 *       a <em>strong</em> copy into {@link org.otel.agent.runtime.EnrichmentRuntime#setState} in
 *       the application class loader. That strong copy is the only thing keeping the {@code
 *       Class<?>} refs in {@link CompiledConfiguration}/{@link RuleIndex} alive; when the loader is
 *       unloaded, those classes die with it. The bridge's weak entry auto-clears via {@link
 *       WeakHashMap}. This breaks the historical Metaspace leak under hot-redeploy (previous
 *       versions stored the state <em>strongly</em> in the long-lived extension loader, pinning
 *       dead application loaders forever).
 *   <li>At method-exit, the instrumentation advice reads {@link
 *       org.otel.agent.runtime.EnrichmentRuntime#state()} directly — a single volatile read, no
 *       {@link #state(ClassLoader)} lookup on the hot path. If reflection strong-push failed at
 *       classLoaderMatcher time (EnrichmentRuntime not loaded yet there), the first enrichment call
 *       adopts the weak-held state from the bridge before GC can reclaim it.
 *   <li>On reload (HTTP POST to the config webserver), {@link #reload(String)} re-parses the XML
 *       for <em>each</em> registered application class loader, forwards the XML to {@link
 *       org.otel.agent.runtime.EnrichmentRuntime#reloadFromBridge(String)} (which runs in the
 *       application class loader, rebuilds the rule index, and {@code setState}s the new snapshot
 *       there), then calls back into {@link #publish} to refresh the bridge's weak entry, {@code
 *       ROOT_NAMES}, and {@code ACTIVE_XML}.
 * </ol>
 *
 * <p>Thread safety: all mutations to {@code STATES}, {@code ROOT_NAMES}, and {@code ACTIVE_XML} are
 * guarded by {@code synchronized(RuntimeBridge.class)}. The {@link #state(ClassLoader)} lookup is a
 * {@link WeakHashMap} read under the same lock; it is only used on cold paths (classLoaderMatcher,
 * tests, webserver-start).
 */
public final class RuntimeBridge {
  private static final RuntimeState DISABLED = RuntimeState.disabled();
  // ponytail: WeakHashMap's key is already weak (auto-clears on ClassLoader GC). The value is a
  // WeakReference too, so even while the loader lives, an outdated RuntimeState (after reload)
  // doesn't stay pinned by the bridge — EnrichmentRuntime.STATE is the only strong holder.
  private static final Map<ClassLoader, WeakReference<RuntimeState>> STATES = new WeakHashMap<>();
  // ponytail: strong boolean per loader — no Class<?> refs, doesn't defeat WeakHashMap. Needed
  // because classLoaderMatcher runs before EnrichmentRuntime is injected; the WeakReference in
  // STATES has no strong backing at that point and can be GC'd before state() reads it.
  private static final Map<ClassLoader, Boolean> ENABLED = new WeakHashMap<>();
  private static final AtomicReference<Set<String>> ROOT_NAMES = new AtomicReference<>(Set.of());
  // ponytail: per-class method-name groups the module's typeInstrumentations() exposes to
  // ByteBuddy for advice installation. Updated atomically with ROOT_NAMES on publish/reload.
  private static final AtomicReference<Map<String, Set<String>>> ROOT_METHODS =
      new AtomicReference<>(Map.of());
  private static final AtomicReference<String> ACTIVE_XML = new AtomicReference<>();
  private static final AtomicBoolean WEBSERVER_STARTED = new AtomicBoolean(false);
  private static final WeakReference<RuntimeState> DISABLED_REF = new WeakReference<>(DISABLED);

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
    final RuntimeState state =
        RuntimeState.enabled(configuration, new ExitPointIndex(configuration));
    STATES.put(loader, new WeakReference<>(state));
    ENABLED.put(loader, Boolean.TRUE);
    final Map<String, Set<String>> methodsByClass = new HashMap<>();
    for (final ExitPoint exitPoint : configuration.exitPoints()) {
      methodsByClass
          .computeIfAbsent(exitPoint.rootClassName(), ignored -> new HashSet<>())
          .add(exitPoint.methodName());
    }
    final Map<String, Set<String>> immutableMethods = new HashMap<>();
    methodsByClass.forEach((name, methods) -> immutableMethods.put(name, Set.copyOf(methods)));
    ROOT_NAMES.set(Set.copyOf(immutableMethods.keySet()));
    ROOT_METHODS.set(Map.copyOf(immutableMethods));
    forwardStateToApplicationClassloader(loader, state);
  }

  public static void initialize(final ClassLoader applicationLoader) {
    // ponytail: the reentry guard is now based on ENABLED, not STATES. prePublishRootMethods
    // inserts a placeholder STATES entry (DISABLED_REF) before the parser's Class.forName runs,
    // so the nested ByteBuddy-triggered classLoaderMatcher call doesn't re-enter initialize and
    // double-parse. The real publish later replaces DISABLED_REF with the enabled RuntimeState.
    synchronized (RuntimeBridge.class) {
      if (ENABLED.getOrDefault(applicationLoader, Boolean.FALSE)) {
        // Already initialised successfully on a previous call; skip.
        final WeakReference<RuntimeState> ref = STATES.get(applicationLoader);
        if (ref != null && ref.get() != null && ref.get() != DISABLED) return;
      }
    }
    final String encoded = System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    if (encoded == null) {
      synchronized (RuntimeBridge.class) {
        STATES.putIfAbsent(applicationLoader, DISABLED_REF);
        ENABLED.putIfAbsent(applicationLoader, Boolean.FALSE);
      }
      return;
    }
    startWebserverIfNeeded();
    // ponytail: publish ROOT_NAMES + ENABLED=true *before* ConfigurationParser.parse runs — parse
    // triggers Class.forName on configured classes, ByteBuddy reintegrates the load via
    // classLoaderMatcher which needs ENABLED=true to install advice on this loader. If the parse
    // fails later we roll back. Without this, ByteBuddy's nested transform during parse sees
    // ENABLED=false and never installs advice, leaving Garage (already loaded) untransformed.
    prePublishRootMethods(encoded, applicationLoader);
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encoded, applicationLoader);
      final String xml = decodeToXml(encoded);
      publish(applicationLoader, configuration, xml);
      logConfig(configuration);
    } catch (final ConfigurationException exception) {
      synchronized (RuntimeBridge.class) {
        STATES.put(applicationLoader, DISABLED_REF);
        ENABLED.put(applicationLoader, Boolean.FALSE);
      }
      // ponytail: do NOT clear ROOT_NAMES/ROOT_METHODS here — another loader may have published
      // them successfully. Clearing here would break the typeMatcher for all subsequent loads,
      // including those of loaders that successfully published. A failed loader gets
      // ENABLED=false so classLoaderMatcher never invokes its advice — global ROOT_NAMES being
      // slightly inaccurate (it contains names that are not applicable on this failed loader)
      // is harmless because the advice gating sees enabled=false and short-circuits in enrich.
    }
  }

  private static void prePublishRootMethods(final String encoded, final ClassLoader loader) {
    try {
      final String xml = decodeToXml(encoded);
      if (xml == null) return;
      final Map<String, Set<String>> methodsByClass = scanEnrichMethods(xml);
      if (methodsByClass.isEmpty()) return;
      // ponytail: set ROOT_NAMES and ENABLED eagerly so the nested Class.forName triggered by
      // the parser's parseDynamic makes ByteBuddy's classLoaderMatcher return true for this
      // loader, and the typeMatcher's hasSuperType match returns true for configured classes.
      // publishLocked below re-confirms with the parsed configuration (idempotent overwrite).
      ROOT_NAMES.set(Set.copyOf(methodsByClass.keySet()));
      synchronized (RuntimeBridge.class) {
        ENABLED.put(loader, Boolean.TRUE);
        // Mark a placeholder state so the reentry guard at the top of initialize doesn't re-run
        // for a class that is being loaded mid-parse. publishLocked will replace this with the
        // real enabled RuntimeState. If the parse fails, the catch block rolls it back to
        // DISABLED.
        STATES.putIfAbsent(loader, DISABLED_REF);
      }
    } catch (final Throwable ignored) {
      // Malformed Base64/XML here is fine — the real parse below will fail with the proper error.
    }
  }

  private static Map<String, Set<String>> scanEnrichMethods(final String xml)
      throws ConfigurationException {
    final Element root = new ConfigurationParser().parseXmlDocument(xml.getBytes(StandardCharsets.UTF_8));
    final Element dynamic = childByName(root, "dynamic");
    if (dynamic == null) return Map.of();
    final Map<String, Set<String>> methodsByClass = new HashMap<>();
    for (final Element enrich : childrenByName(dynamic, "enrich")) {
      final String className = enrich.getAttribute("class");
      final String methodName = enrich.getAttribute("method");
      if (className.isBlank() || methodName.isBlank()) continue;
      methodsByClass.computeIfAbsent(className, ignored -> new HashSet<>()).add(methodName);
    }
    return methodsByClass;
  }

  private static Element childByName(final Element parent, final String name) {
    for (final Element child : childrenByName(parent, null)) {
      if (name == null || name.equals(child.getTagName())) return child;
    }
    return null;
  }

  private static List<Element> childrenByName(final Element parent, final String name) {
    final List<Element> result = new ArrayList<>();
    final org.w3c.dom.NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final org.w3c.dom.Node node = nodes.item(i);
      if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
          && (name == null || name.equals(node.getNodeName()))) {
        result.add((Element) node);
      }
    }
    return result;
  }

  public static ReloadResult reload(final String xml) {
    final List<ClassLoader> loaders;
    synchronized (RuntimeBridge.class) {
      loaders = new ArrayList<>(STATES.keySet());
    }
    final List<String> failures = new ArrayList<>();
    int updated = 0;
    int staticCount = 0;
    int exitCount = 0;

    for (final ClassLoader loader : loaders) {
      try {
        final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
        if (!forwardReloadToApplicationClassloader(xml, loader)) {
          failures.add("reload failed in application classloader");
          continue;
        }
        // forwardReloadToApplicationClassloader cascades into EnrichmentRuntime.reloadFromBridge,
        // which itself calls back RuntimeBridge.publish() — that path stores the WeakReference
        // and strong-pushes into EnrichmentRuntime.STATE. No strong publish here (would leak).
        updated++;
        staticCount = configuration.staticRules().size();
        exitCount = configuration.exitPoints().size();
      } catch (final ConfigurationException exception) {
        failures.add(category(exception));
      }
    }

    return new ReloadResult(updated, staticCount, exitCount, failures);
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

  // ponytail: strong Push the RuntimeState into the per-loader EnrichmentRuntime.STATE so that
  // the long-lived extension loader never holds the only strong reference to the Class<?> refs
  // inside CompiledConfiguration/ExitPointIndex. Cold path (startup + reload). Fails harmlessly
  // if EnrichmentRuntime isn't loaded yet — the first enrich() call will adopt via state(loader).
  private static void forwardStateToApplicationClassloader(
      final ClassLoader loader, final RuntimeState state) {
    try {
      final Class<?> enrichmentRuntime =
          Class.forName("org.otel.agent.runtime.EnrichmentRuntime", true, loader);
      enrichmentRuntime.getMethod("setState", RuntimeState.class).invoke(null, state);
    } catch (final InvocationTargetException setStateFailure) {
      System.getLogger(RuntimeBridge.class.getName())
          .log(System.Logger.Level.WARNING, "state strong-push failed", setStateFailure.getCause());
    } catch (final ReflectiveOperationException notInjected) {
      // Expected when called from classLoaderMatcher before helper injection. The first
      // EnrichmentRuntime.enrich() will adopt from the bridge's WeakReference.
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
            "enabled static={0} exit-points={1} roots={2}",
            configuration.staticRules().size(),
            configuration.exitPoints().size(),
            configuration.exitPoints().stream()
                .map(ExitPoint::rootClass)
                .distinct()
                .count());
  }

  public static RuntimeState state(final ClassLoader loader) {
    synchronized (RuntimeBridge.class) {
      final WeakReference<RuntimeState> ref = STATES.get(loader);
      if (ref == null) return DISABLED;
      final RuntimeState held = ref.get();
      return held != null ? held : DISABLED;
    }
  }

  public static boolean enabled(final ClassLoader loader) {
    synchronized (RuntimeBridge.class) {
      return ENABLED.getOrDefault(loader, Boolean.FALSE);
    }
  }

  public static Set<String> rootClassNames() {
    return ROOT_NAMES.get();
  }

  public static Map<String, Set<String>> rootClassMethods() {
    return ROOT_METHODS.get();
  }

  public static void resetForTesting() {
    ConfigWebserver.stop();
    WEBSERVER_STARTED.set(false);
    synchronized (RuntimeBridge.class) {
      STATES.clear();
      ENABLED.clear();
    }
    ROOT_NAMES.set(Set.of());
    ROOT_METHODS.set(Map.of());
    ACTIVE_XML.set(null);
  }
}
