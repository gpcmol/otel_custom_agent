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

/**
 * ByteBuddy {@link TypeInstrumentation} that adds trace-attribute enrichment advice to
 * application classes matching the configured dynamic rules.
 *
 * <p>Type matching: {@link #typeMatcher()} delegates to a cached
 * {@link ElementMatcher} built from the root class names in the active configuration
 * (via {@link RuntimeBridge#rootClassNames()}). The matcher uses
 * {@code hasSuperType} semantics — a class matches if any of its super-types (interfaces or
 * superclass) is one of the configured root classes. The matcher is rebuilt only when the
 * root class name set changes identity (checked via reference equality on the volatile
 * {@code cachedRoots} field), avoiding repeated ByteBuddy walks on every type load.
 *
 * <p>Method matching: {@link #transform(TypeTransformer)} applies
 * {@link TraceAttributeAdvice#onExit(Object)} as an {@code @Advice.OnMethodExit} to every
 * non-static, non-abstract, non-native, non-bridge, non-synthetic instance method. The advice
 * calls {@link EnrichmentRuntime#enrich(Object)} with the method receiver, which resolves
 * configured dynamic attribute paths against the receiver and writes the results as span
 * attributes.
 *
 * <p>Performance: the {@code typeMatcher} short-circuits to {@code false} when no root classes
 * are configured, avoiding any ByteBuddy work. The {@code OnMethodExit} advice is suppressed
 * on all exceptions to ensure enrichment never affects application behavior.
 */
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
