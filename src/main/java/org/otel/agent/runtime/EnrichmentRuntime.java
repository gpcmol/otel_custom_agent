package org.otel.agent.runtime;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.model.StaticAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.accessor.AccessorCache;
import org.otel.agent.runtime.resolver.ValueResolver;
import org.otel.agent.telemetry.AttributeConverter;
import org.otel.agent.telemetry.AttributeValue;
import org.otel.agent.telemetry.SpanWriter;

public final class EnrichmentRuntime {
  private static final ValueResolver RESOLVER = new ValueResolver(new AccessorCache());
  private static final AttributeConverter CONVERTER = new AttributeConverter();
  private static final SpanWriter WRITER = new SpanWriter();
  private static final ThreadLocal<Boolean> ENRICHING = ThreadLocal.withInitial(() -> false);
  private static volatile boolean initialized;

  private EnrichmentRuntime() {}

  public static void enrich(final Object receiver) {
    if (receiver == null) return;
    if (ENRICHING.get()) return;
    ENRICHING.set(true);
    try {
      final ClassLoader applicationLoader = receiver.getClass().getClassLoader();
      if (!initialized) {
        RuntimeBridge.initialize(applicationLoader);
        initialized = true;
      }
      final RuntimeState state = RuntimeBridge.state(applicationLoader);
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
      ENRICHING.set(false);
    }
  }

  public static void reloadFromBridge(final String xml) throws ConfigurationException {
    final ClassLoader applicationLoader = EnrichmentRuntime.class.getClassLoader();
    final CompiledConfiguration configuration =
        new ConfigurationParser().parseXml(xml, applicationLoader);
    RuntimeBridge.publish(applicationLoader, configuration, xml);
  }

  private static void createFallback(final Object receiver, final RuntimeState state) {
    final Tracer tracer = GlobalOpenTelemetry.getTracer("org.otel.custom-agent");
    Span span = null;
    try {
      span =
          tracer
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

  private static void write(final Span span, final Object receiver, final RuntimeState state) {
    for (final StaticAttributeRule rule : state.configuration().staticRules()) {
      // Static values are always strings per the XML contract; no conversion needed.
      WRITER.write(span, rule.key(), new AttributeValue(rule.value()));
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
}
