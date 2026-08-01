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
import static net.bytebuddy.matcher.ElementMatchers.returns;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.lang.reflect.Method;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.runtime.EnrichmentRuntime;

/**
 * ByteBuddy {@link TypeInstrumentation} that installs exit-point enrichment advice on configured
 * application classes.
 *
 * <p><b>ByteBuddy timing constraint</b>: ByteBuddy installs method matchers at agent bootstrap
 * time — before {@link RuntimeBridge#initialize} publishes the configured exit-point method
 * names via {@code classLoaderMatcher}. As a result, {@link #transform} cannot read
 * {@link RuntimeBridge#rootClassMethods()} to install advice only on declared exit-point
 * methods (the set is empty at install time and not re-evaluated later). To work around this,
 * advice is installed on every non-static instance method of matching types, and
 * {@link EnrichmentRuntime#enrich} filters via {@link
 * org.otel.agent.runtime.model.ExitPointIndex#find} at runtime. If no rules are configured for
 * the actual {@code (receiverClass, methodName)} pair, the index returns an empty list and the
 * enrich call short-circuits before any {@code setAttribute} work — roughly the cost of one
 * bounded-cache lookup per call (&lt;1μs).
 *
 * <p><b>Type matching</b>: {@code hasSuperType} walks the supertype hierarchy; the inner matcher
 * checks if any supertype's name is a configured exit-point class (via
 * {@link RuntimeBridge#rootClassNames}). Cached by config identity, matching the previous model.
 *
 * <p><b>Split void/non-void advice</b>: ByteBuddy refuses to install {@code @Advice.Return Object}
 * on void methods ("Cannot assign void to class java.lang.Object"). Two advice classes are
 * installed: one for void methods (no {@code @Advice.Return} parameter; {@code $return} rules
 * resolve to null at runtime) and one for non-void methods.
 *
 * <p>Performance: the {@code typeMatcher} short-circuits to {@code false} when no exit points are
 * configured. The {@code OnMethodExit} advice is suppressed on all exceptions to ensure
 * enrichment never affects application behavior. The runtime filter in {@code enrich} is the
 * key performance gate: non-exit-point method calls do O(1) cache lookup and return.
 */
final class TraceAttributeTypeInstrumentation implements TypeInstrumentation {
  // ponytail: lazily cached matcher keyed by root-class-names identity. ByteBuddy calls
  // typeMatcher().matches(type) for every type load — we rebuild the compiled matcher only when
  // the published root-class-names set changes (reference equality on a volatile field).
  private static volatile Set<String> cachedRoots;
  private static volatile ElementMatcher.Junction<TypeDescription> cachedMatcher;

  @Override
  public ElementMatcher.Junction<TypeDescription> typeMatcher() {
    return new ElementMatcher.Junction.AbstractBase<>() {
      @Override
      public boolean matches(final TypeDescription type) {
        return matcher().matches(type);
      }
    };
  }

  private static ElementMatcher.Junction<TypeDescription> matcher() {
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
      cachedMatcher =
          hasSuperType(
              new ElementMatcher.Junction.AbstractBase<>() {
                @Override
                public boolean matches(final TypeDescription target) {
                  return target != null && roots.contains(target.getName());
                }
              });
    }
    return cachedMatcher;
  }

  @Override
  public void transform(final TypeTransformer transformer) {
    final net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.method.MethodDescription>
        base =
            isMethod()
                .and(not(isConstructor()))
                .and(not(isStatic()))
                .and(not(isAbstract()))
                .and(not(isNative()))
                .and(not(isBridge()))
                .and(not(isSynthetic()));
    // ponytail: ByteBuddy raises "Cannot assign void to class java.lang.Object" if
    // @Advice.Return Object is declared on a void method. Split the advices by return type:
    // void methods get the VoidAdvice (no @Advice.Return, $return rules resolve to null at
    // runtime); non-void methods get the ValueAdvice (with @Advice.Return Object). Both forward
    // to EnrichmentRuntime.enrich with null for the value-less caller.
    transformer.applyAdviceToMethod(
        base.and(returns(void.class)),
        TraceAttributeTypeInstrumentation.class.getName() + "$VoidAdvice");
    transformer.applyAdviceToMethod(
        base.and(not(returns(void.class))),
        TraceAttributeTypeInstrumentation.class.getName() + "$ValueAdvice");
  }

  /** Advice for void methods — no {@code @Advice.Return} parameter (ByteBuddy can't assign void). */
  public static class VoidAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Object receiver,
        @Advice.AllArguments final Object[] arguments,
        @Advice.Origin final Method method) {
      EnrichmentRuntime.enrich(receiver, arguments, null, method.getName());
    }
  }

  /** Advice for non-void methods — {@code @Advice.Return Object} captures the return value. */
  public static class ValueAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final Object receiver,
        @Advice.AllArguments final Object[] arguments,
        @Advice.Return final Object returned,
        @Advice.Origin final Method method) {
      EnrichmentRuntime.enrich(receiver, arguments, returned, method.getName());
    }
  }
}