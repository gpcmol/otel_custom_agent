package org.otel.agent.instrumentation;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.bridge.RuntimeBridge;

@AutoService(InstrumentationModule.class)
public final class TraceAttributeInstrumentationModule extends InstrumentationModule {
  public TraceAttributeInstrumentationModule() {
    super("trace-attribute-enrichment");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return new ElementMatcher.Junction.AbstractBase<>() {
      @Override
      public boolean matches(final ClassLoader loader) {
        RuntimeBridge.initialize(loader);
        return RuntimeBridge.state(loader).enabled();
      }
    };
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return List.of(new TraceAttributeTypeInstrumentation());
  }

  @Override
  public List<String> getAdditionalHelperClassNames() {
    return List.of(
            "org.otel.agent.bridge.RuntimeBridge",
            "org.otel.agent.bridge.RuntimeState",
            "org.otel.agent.config.model.CompiledConfiguration",
            "org.otel.agent.config.model.DynamicAttributeRule",
            "org.otel.agent.config.model.IndexedPropertySegment",
            "org.otel.agent.config.model.PathSegment",
            "org.otel.agent.config.model.PropertySegment",
            "org.otel.agent.config.model.StaticAttributeRule",
            "org.otel.agent.config.parser.ConfigurationException",
            "org.otel.agent.config.parser.ConfigurationParser",
            "org.otel.agent.runtime.EnrichmentRuntime",
            "org.otel.agent.runtime.accessor.Accessor",
            "org.otel.agent.runtime.accessor.AccessorCache",
            "org.otel.agent.runtime.accessor.AccessorCache$Key",
            "org.otel.agent.runtime.cache.FifoCache",
            "org.otel.agent.runtime.model.RuleIndex",
            "org.otel.agent.runtime.resolver.ValueResolver",
            "org.otel.agent.telemetry.AttributeConverter",
            "org.otel.agent.telemetry.AttributeValue",
            "org.otel.agent.telemetry.SpanWriter",
            "org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation$TraceAttributeAdvice");
  }
}
