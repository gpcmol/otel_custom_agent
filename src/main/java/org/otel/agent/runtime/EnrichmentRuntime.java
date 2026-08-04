package org.otel.agent.runtime;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.model.ExitRule;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.config.model.StaticAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.EvalContext;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.model.ExitPointIndex;
import org.otel.agent.runtime.resolver.ValueResolver;
import org.otel.agent.telemetry.AttributeValue;
import org.otel.agent.telemetry.SpanWriter;

/**
 * Runtime enrichment engine that resolves configured attribute paths against instrumented objects
 * and writes the results as span attributes.
 *
 * <p>Entry point: called from instrumentation advice via {@link
 * org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation.TraceAttributeAdvice} on the
 * exit of a declared {@code <enrich class method>}. The
 * {@link #enrich(Object, Object[], Object, String)} method:
 *
 * <ol>
 *   <li>Initializes the agent bridge on first call (lazy startup)
 *   <li>Reads the per-loader {@link #STATE} snapshot (a single volatile read — no class-loader
 *       lookup on the hot path after warmup)
 *   <li>Selects the applicable {@link ExitRule}s for {@code (receiver.getClass(), methodName)}
 *       via {@link ExitPointIndex#find}
 *   <li>For each rule, resolves the root from the {@link RootSource} anchor
 *       ({@code $this} / {@code $argN} / {@code $return}) and walks the segments via
 *       {@link ValueResolver}
 *   <li>Writes static attributes (cached per configuration identity) and resolved dynamic
 *       attributes to the current span — once per enrich event
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
 * CompiledConfiguration}/{@link ExitPointIndex}). When the loader is unloaded the statics die
 * with it. The bridge ({@link RuntimeBridge}, in the long-lived agent extension class loader)
 * keeps only a {@link java.lang.ref.WeakReference} to this state, so it can never pin a dead
 * loader's classes — the historical Metaspace leak on hot-redeploy.
 *
 * <p>Failure isolation: all exceptions are swallowed — enrichment must never affect application
 * behavior. Individual dynamic rule failures do not block other rules.
 */
public final class EnrichmentRuntime {
  private static final ValueResolver RESOLVER = new ValueResolver(new AccessorCache());
  private static final SpanWriter WRITER = new SpanWriter();
  private static final Tracer FALLBACK_TRACER =
      GlobalOpenTelemetry.getTracer("org.otel.custom-agent");
  private static final ThreadLocal<Boolean> ENRICHING = ThreadLocal.withInitial(() -> false);
  private static volatile boolean initialized;

  // ponytail: per-loader sterke houder van RuntimeState. Staat in de app-loader (deze class
  // wordt per app-loader geïnjecteerd), dus de Class<?> refs in CompiledConfiguration/ExitPointIndex
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

  public static void enrich(final Object receiver, final Object[] arguments, final Object returned, final String methodName) {
    if (receiver == null) return;
    if (!initialized) {
      final ClassLoader appLoader = receiver.getClass().getClassLoader();
      RuntimeBridge.initialize(appLoader);
      final RuntimeState fromBridge = RuntimeBridge.state(appLoader);
      if (fromBridge.enabled() && fromBridge != STATE) STATE = fromBridge;
      initialized = true;
    }
    final RuntimeState state = STATE;
    if (!state.enabled()) return;
    final List<ExitRule> rules = state.exitIndex().find(receiver.getClass(), methodName);
    if (rules.isEmpty()) return;
    final ExitPoint exitPoint =
        state.exitIndex().findExitPoint(receiver.getClass(), methodName);
    if (!gate(exitPoint, receiver, arguments, returned)) return;
    if (ENRICHING.get()) return;
    ENRICHING.set(true);
    try {
      final Span current = Span.current();
      if (current.getSpanContext().isValid()) {
        write(current, receiver, arguments, returned, rules, state);
        return;
      }
      createFallback(receiver, arguments, returned, rules, state);
    } catch (final Throwable ignored) {
      // Enrichment must never affect application behavior.
    } finally {
      ENRICHING.set(false);
    }
  }

  public static void reloadFromBridge(final String xml) throws ConfigurationException {
    final ClassLoader applicationLoader = EnrichmentRuntime.class.getClassLoader();
    final CompiledConfiguration configuration =
        new ConfigurationParser().parseXml(xml, applicationLoader);
    final ExitPointIndex exitIndex = new ExitPointIndex(configuration);
    setState(RuntimeState.enabled(configuration, exitIndex));
    RuntimeBridge.publish(applicationLoader, configuration, xml);
  }

  /**
   * The single runtime evaluation point for the condition DSL.
   *
   * <p>Returns {@code true} when the block should enrich (condition is {@code null} — absent or
   * silently disabled — or evaluates to {@code true}); returns {@code false} when the block must
   * be skipped (condition evaluates to {@code false} or throws). This is the ONLY place the DSL
   * is evaluated at runtime — the parser runs only at startup/reload.
   *
   * <p>The {@link EvalContext} is stack-allocated here and passed by reference through the AST
   * walk — no per-node allocation.
   *
   * @param exitPoint the matched exit point, or {@code null} if no exit point was found
   * @param receiver the advice receiver ({@code @Advice.This})
   * @param arguments the advice arguments ({@code @Advice.AllArguments})
   * @param returned the advice return value ({@code @Advice.Return}, or {@code null} for void)
   * @return {@code true} if the block should enrich; {@code false} to skip
   */
  static boolean gate(
      final ExitPoint exitPoint,
      final Object receiver,
      final Object[] arguments,
      final Object returned) {
    if (exitPoint == null) return true;
    final Condition condition = exitPoint.condition();
    if (condition == null) return true;
    final EvalContext ctx = new EvalContext(receiver, arguments, returned, RESOLVER);
    try {
      return condition.eval(ctx);
    } catch (final Throwable ignored) {
      return false;
    }
  }

  private static void createFallback(final Object receiver, final Object[] arguments, final Object returned, final List<ExitRule> rules, final RuntimeState state) {
    Span span = null;
    try {
      span =
          FALLBACK_TRACER
              .spanBuilder("otel.custom-agent.enrichment")
              .setSpanKind(SpanKind.INTERNAL)
              .startSpan();
      write(span, receiver, arguments, returned, rules, state);
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

  private static void write(final Span span, final Object receiver, final Object[] arguments, final Object returned, final List<ExitRule> rules, final RuntimeState state) {
    if (state != cachedState) {
      buildStaticCache(state);
      cachedState = state;
    }
    final String[] keys = staticKeys;
    final AttributeValue[] attrs = staticValues;
    for (int i = 0; i < keys.length; i++) {
      WRITER.write(span, keys[i], attrs[i]);
    }
    for (final ExitRule rule : rules) {
      try {
        final Object root = rootOf(rule.rootSource(), receiver, arguments, returned);
        if (root == null) continue;
        final Object resolved = RESOLVER.resolve(root, rule.segments());
        WRITER.write(span, rule.key(), resolved);
      } catch (final Throwable ignored) {
        // One broken rule must not block the remaining rules.
      }
    }
  }

  private static Object rootOf(
      final RootSource rootSource, final Object receiver, final Object[] arguments, final Object returned) {
    return switch (rootSource) {
      case RootSource.This ignored -> receiver;
      case RootSource.Argument arg -> arg.index() < arguments.length ? arguments[arg.index()] : null;
      case RootSource.ReturnValue ignored -> returned;
    };
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
