package org.otel.agent.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.runtime.EnrichmentRuntime;

final class TraceAttributeTypeInstrumentation implements TypeInstrumentation {
  // ponytail: one cached matcher per published root-name-set identity. A single
  // hasSuperType walk tests every level via an O(1) name lookup instead of N walks.
  private static volatile Set<String> cachedRoots;
  private static volatile ElementMatcher.Junction<TypeDescription> cachedMatcher;

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return new ElementMatcher.Junction.AbstractBase<>() {
      @Override
      public boolean matches(final TypeDescription type) {
        return matcher().matches(type);
      }
    };
  }

  private static ElementMatcher<TypeDescription> matcher() {
    final Set<String> roots = RuntimeBridge.rootClassNames();
    if (roots.isEmpty()) {
      return new ElementMatcher.Junction.AbstractBase<>() {
        @Override
        public boolean matches(final TypeDescription type) {
          return false;
        }
      };
    }
    if (roots != cachedRoots) {
      cachedRoots = roots;
      cachedMatcher = hasSuperType(new NameSetMatcher(roots));
    }
    return cachedMatcher;
  }

  private static final class NameSetMatcher
      extends ElementMatcher.Junction.AbstractBase<TypeDescription> {
    private final Set<String> names;

    NameSetMatcher(final Set<String> names) {
      this.names = names;
    }

    @Override
    public boolean matches(final TypeDescription target) {
      if (target == null) return false;
      return names.contains(target.getName());
    }
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
