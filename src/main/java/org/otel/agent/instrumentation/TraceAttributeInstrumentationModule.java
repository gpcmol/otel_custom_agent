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
 * contains at least one {@code <enrich>} exit point (checked via {@link RuntimeBridge#state}).
 *
 * <p>ClassLoader gating: {@link #classLoaderMatcher()} calls
 * {@link RuntimeBridge#initialize(ClassLoader)} on first encounter with each application
 * class loader, then checks {@link RuntimeBridge#state(ClassLoader).enabled()} to decide
 * whether instrumentation should be applied to that loader.
 *
 * <p>Type instrumentations: returns a single {@link TraceAttributeTypeInstrumentation} that reads
 * the configured exit-point classes and methods <em>lazily</em> at match time. The OTel agent
 * calls this method once during module setup — before {@code classLoaderMatcher} has loaded the
 * config — so the instrumentation cannot build per-class instances eagerly. Instead the single
 * instrumentation reads {@link RuntimeBridge#rootClassMethods()} fresh every time a type is
 * loaded, caching the compiled matcher by config identity (same pattern as the previous model).
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
        return RuntimeBridge.enabled(loader);
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
        "org.otel.agent.config.model.ExitPoint",
        "org.otel.agent.config.model.ExitRule",
        "org.otel.agent.config.model.IndexedPropertySegment",
        "org.otel.agent.config.model.PathSegment",
        "org.otel.agent.config.model.PropertySegment",
        "org.otel.agent.config.model.RootSource",
        "org.otel.agent.config.model.RootSource$This",
        "org.otel.agent.config.model.RootSource$Argument",
        "org.otel.agent.config.model.RootSource$ReturnValue",
        "org.otel.agent.config.model.StaticAttributeRule",
        "org.otel.agent.config.parser.ConfigurationException",
        "org.otel.agent.config.parser.ConfigurationParser",
        "org.otel.agent.expr.Condition",
        "org.otel.agent.expr.Leaf",
        "org.otel.agent.expr.EvalContext",
        "org.otel.agent.expr.ExpressionCompileException",
        "org.otel.agent.expr.And",
        "org.otel.agent.expr.Or",
        "org.otel.agent.expr.Not",
        "org.otel.agent.expr.Compare",
        "org.otel.agent.expr.Compare$Op",
        "org.otel.agent.expr.In",
        "org.otel.agent.expr.BoolCall",
        "org.otel.agent.expr.BoolCall$Fn",
        "org.otel.agent.expr.Literal",
        "org.otel.agent.expr.Property",
        "org.otel.agent.expr.Arithmetic",
        "org.otel.agent.expr.Arithmetic$Op",
        "org.otel.agent.expr.SizeCall",
        "org.otel.agent.expr.LikeMatcher",
        "org.otel.agent.expr.parser.ExpressionParser",
        "org.otel.agent.expr.parser.ExpressionParser$1",
        "org.otel.agent.expr.parser.Lexer",
        "org.otel.agent.expr.parser.Lexer$Token",
        "org.otel.agent.expr.parser.Lexer$Token$Type",
        "org.otel.agent.expr.parser.TypeChecker",
        "org.otel.agent.expr.parser.TypeChecker$1",
        "org.otel.agent.runtime.EnrichmentRuntime",
        "org.otel.agent.runtime.accessor.Accessor",
        "org.otel.agent.runtime.accessor.AccessorCache",
        "org.otel.agent.runtime.accessor.AccessorCache$Key",
        "org.otel.agent.runtime.cache.FifoCache",
        "org.otel.agent.runtime.model.ExitPointIndex",
        "org.otel.agent.runtime.model.ExitPointKey",
        "org.otel.agent.runtime.resolver.ValueResolver",
        "org.otel.agent.telemetry.AttributeConverter",
        "org.otel.agent.telemetry.AttributeValue",
        "org.otel.agent.telemetry.SpanWriter",
        "org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation$VoidAdvice",
        "org.otel.agent.instrumentation.TraceAttributeTypeInstrumentation$ValueAdvice",
        "org.otel.agent.utils.Base64Util",
        "org.otel.agent.webserver.ConfigWebserver",
        "org.otel.agent.webserver.ConfigPage");
  }
}
