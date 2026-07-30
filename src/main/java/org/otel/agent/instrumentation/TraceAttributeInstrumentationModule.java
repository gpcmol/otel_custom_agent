package org.otel.agent.instrumentation;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.bridge.RuntimeBridge;

/**
 * OpenTelemetry Java agent instrumentation module for trace attribute enrichment.
 *
 * <p>Registered via {@link AutoService} so the OTel agent discovers it at startup. The module is
 * named {@code "trace-attribute-enrichment"} and is only active when the loaded configuration
 * contains at least one dynamic rule (checked via {@link RuntimeBridge#state}).
 *
 * <p>ClassLoader gating: {@link #classLoaderMatcher()} calls
 * {@link RuntimeBridge#initialize(ClassLoader)} on first encounter with each application
 * class loader, then checks {@link RuntimeBridge#state(ClassLoader).enabled()} to decide
 * whether instrumentation should be applied to that loader.
 *
 * <p>Helper classes: {@link #getAdditionalHelperClassNames()} declares all agent-internal
 * classes that must be visible in the application class loader's namespace when the ByteBuddy
 * advice executes. This includes the bridge, config model, runtime, telemetry, and webserver
 * classes — everything the advice code references at runtime.
 *
 * <p>Thread safety: the module instance is shared across all class loaders. The
 * {@code classLoaderMatcher} is called concurrently for different loaders;
 * {@link RuntimeBridge#initialize} is internally synchronized.
 */
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
        "org.otel.agent.bridge.ReloadResult",
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
        "org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation$TraceAttributeAdvice",
        "org.otel.agent.utils.Base64Util",
        "org.otel.agent.webserver.ConfigWebserver",
        "org.otel.agent.webserver.ConfigPage");
  }
}
