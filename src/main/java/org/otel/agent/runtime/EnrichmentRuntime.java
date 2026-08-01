package org.otel.agent.runtime;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.model.StaticAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.model.RuleIndex;
import org.otel.agent.runtime.resolver.ValueResolver;
import org.otel.agent.telemetry.AttributeConverter;
import org.otel.agent.telemetry.AttributeValue;
import org.otel.agent.telemetry.SpanWriter;

/**
 * Runtime enrichment engine that resolves configured attribute paths against instrumented objects
 * and writes the results as span attributes.
 *
 * <p>Entry point: called from instrumentation advice via {@link
 * org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation.TraceAttributeAdvice}. The
 * {@link #enrich(Object)} method:
 *
 * <ol>
 *   <li>Initializes the agent bridge on first call (lazy startup)
 *   <li>Reads the per-loader {@link #STATE} snapshot (a single volatile read — no class-loader
 *       lookup on the hot path after warmup)
 *   <li>Writes static attributes (cached per configuration identity) and dynamic attributes
 *       (resolved per receiver via {@link ValueResolver}) to the current span
 * </ol>
 *
 * <p>Fallback: if no valid span context is available (e.g. outside a trace), a fallback {@code
 * INTERNAL} span is created via the OTel global tracer so attributes are still emitted.
 *
 * <p>Reentrancy guard: a {@link ThreadLocal} boolean prevents recursive enrichment when attribute
 * resolution itself triggers instrumented methods.
 *
 * <p>Static cache: {@code staticKeys}/{@code staticValues} arrays are rebuilt only when the {@link
 * RuntimeState} identity changes (checked via reference equality on the volatile {@code
 * cachedState} field), avoiding per-span allocation in steady state.
 *
 * <p>State ownership (memory-leak guard): {@link #STATE} lives in <em>this</em> class, which is
 * injected per application class loader. It holds the only <strong>strong</strong> reference to the
 * active {@link RuntimeState} (and hence to the {@code Class<?>} refs in {@link
 * CompiledConfiguration}/{@link org.otel.agent.runtime.model.RuleIndex}). When the loader is
 * unloaded the statics die with it. The bridge ({@link RuntimeBridge}, in the long-lived agent
 * extension class loader) keeps only a {@link java.lang.ref.WeakReference} to this state, so it can
 * never pin a dead loader's classes — the historical Metaspace leak on hot-redeploy.
 *
 * <p>Failure isolation: all exceptions are swallowed — enrichment must never affect application
 * behavior. Individual dynamic rule failures do not block other rules.
 */
public final class EnrichmentRuntime {
  private static final ValueResolver RESOLVER = new ValueResolver(new AccessorCache());
  private static final AttributeConverter CONVERTER = new AttributeConverter();
  private static final SpanWriter WRITER = new SpanWriter();
  private static final Tracer FALLBACK_TRACER =
      GlobalOpenTelemetry.getTracer("org.otel.custom-agent");
  private static final ThreadLocal<Boolean> ENRICHING = ThreadLocal.withInitial(() -> false);
  private static volatile boolean initialized;

  // ponytail: per-loader sterke houder van RuntimeState. Staat in de app-loader (deze class
  // wordt per app-loader geïnjecteerd), dus de Class<?> refs in CompiledConfiguration/RuleIndex
  // sterven met de loader. RuntimeBridge houdt enkel WeakReference<RuntimeState> bij.
  private static volatile RuntimeState STATE = RuntimeState.disabled();

  private EnrichmentRuntime() {}

  /**
   * Strong-publish entry for the bridge: replaces the active state atomically. Called from the
   * long-lived extension class loader via reflection; cold path (startup/reload only).
   */
  public static void setState(final RuntimeState newState) {
    STATE = newState;
  }

  /** Test/inspection accessor for the active per-loader state. */
  public static RuntimeState state() {
    return STATE;
  }

  public static void enrich(final Object receiver) {
    if (receiver == null) return;
    if (ENRICHING.get()) return;
    ENRICHING.set(true);
    try {
      if (!initialized) {
        final ClassLoader appLoader = receiver.getClass().getClassLoader();
        RuntimeBridge.initialize(appLoader);
        // ponytail: the bridge's strong-push reflection runs from classLoaderMatcher-time where
        // EnrichmentRuntime isn't loaded yet, so it fails; meanwhile the bridge keeps only a
        // WeakReference. We are now in-app and can adopt the bridge's held state before GC
        // reclaims it. Cold path (once per loader); hot path reads STATE directly.
        final RuntimeState fromBridge = RuntimeBridge.state(appLoader);
        if (fromBridge.enabled() && fromBridge != STATE) STATE = fromBridge;
        initialized = true;
      }
      final RuntimeState state = STATE;
      if (!state.enabled()) return;
      final Span current = Span.current();
      if (current.getSpanContext().isValid()) {
        write(current, receiver, state);
        return;
      }
      createFallback(receiver, state);
    } catch (final Throwable ignored) {
      // Enrichment must never affect application behavior.
    } finally {
      ENRICHING.remove();
    }
  }

  public static void reloadFromBridge(final String xml) throws ConfigurationException {
    final ClassLoader applicationLoader = EnrichmentRuntime.class.getClassLoader();
    final CompiledConfiguration configuration =
        new ConfigurationParser().parseXml(xml, applicationLoader);
    final RuleIndex ruleIndex = new RuleIndex(configuration);
    setState(RuntimeState.enabled(configuration, ruleIndex));
    RuntimeBridge.publish(applicationLoader, configuration, xml);
  }

  private static void createFallback(final Object receiver, final RuntimeState state) {
    Span span = null;
    try {
      span =
          FALLBACK_TRACER
              .spanBuilder("otel.custom-agent.enrichment")
              .setSpanKind(SpanKind.INTERNAL)
              .startSpan();
      write(span, receiver, state);
    } catch (final Throwable ignored) {
      System.getLogger(EnrichmentRuntime.class.getName())
          .log(System.Logger.Level.WARNING, "fallback span creation failed");
    } finally {
      if (span != null) span.end();
    }
  }

  // ponytail: prebuilt static attributes are immutable per config; rebuild only when the
  // active RuntimeState identity changes. Steady state reuses the array with zero allocation.
  private static volatile RuntimeState cachedState;
  private static volatile String[] staticKeys;
  private static volatile AttributeValue[] staticValues;

  private static void write(final Span span, final Object receiver, final RuntimeState state) {
    if (state != cachedState) {
      buildStaticCache(state);
      cachedState = state;
    }
    final String[] keys = staticKeys;
    final AttributeValue[] attrs = staticValues;
    for (int i = 0; i < keys.length; i++) {
      WRITER.write(span, keys[i], attrs[i]);
    }
    for (final DynamicAttributeRule rule : state.ruleIndex().applicable(receiver.getClass())) {
      try {
        final Object resolved = RESOLVER.resolve(receiver, rule.segments());
        WRITER.write(span, rule.key(), CONVERTER.convert(resolved));
      } catch (final Throwable ignored) {
        // One broken rule must not block the remaining rules.
      }
    }
  }

  private static synchronized void buildStaticCache(final RuntimeState state) {
    if (state == cachedState) return;
    final List<StaticAttributeRule> rules = state.configuration().staticRules();
    final String[] keys = new String[rules.size()];
    final AttributeValue[] values = new AttributeValue[rules.size()];
    for (int i = 0; i < rules.size(); i++) {
      keys[i] = rules.get(i).key();
      values[i] = new AttributeValue(rules.get(i).value());
    }
    staticKeys = keys;
    staticValues = values;
  }
}
