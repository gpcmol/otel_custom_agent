package org.otel.agent.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.runtime.EnrichmentRuntime;

final class TraceAttributeTypeInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return new ElementMatcher.Junction.AbstractBase<>() {
      @Override
      public boolean matches(final TypeDescription type) {
        return RuntimeBridge.rootClassNames().stream()
                .anyMatch(
                        name -> type.getName().equals(name) || hasSuperType(named(name)).matches(type));
      }
    };
  }

  @Override
  public void transform(final TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
            isMethod()
                    .and(not(isConstructor()))
                    .and(not(isStatic()))
                    .and(not(isAbstract()))
                    .and(not(isNative()))
                    .and(not(isBridge()))
                    .and(not(isSynthetic())),
            TraceAttributeTypeInstrumentation.class.getName() + "$TraceAttributeAdvice");
  }

  public static class TraceAttributeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This final Object receiver) {
      EnrichmentRuntime.enrich(receiver);
    }
  }
}
