package org.otel.agent.expr;

import java.util.List;
import org.otel.agent.config.model.PathSegment;
import org.otel.agent.config.model.RootSource;

/**
 * A path-rooted property access ({@code $this.brand}, {@code $arg0.passengers[1].name}).
 *
 * <p>Resolves via the shared {@link org.otel.agent.runtime.resolver.ValueResolver} from the
 * {@link EvalContext}, starting at the {@link RootSource} anchor and walking the compiled
 * {@link PathSegment} list. The {@code leafType} is the reflected return type of the final
 * property getter, determined at parse-time by the {@link org.otel.agent.expr.parser.TypeChecker}
 * and used for type-checking the enclosing operator.
 */
public record Property(RootSource root, List<PathSegment> segments, Class<?> leafType)
    implements Leaf {
  public Property {
    segments = List.copyOf(segments);
  }

  @Override
  public Object read(final EvalContext ctx) {
    final Object rootObj =
        switch (root) {
          case RootSource.This ignored -> ctx.receiver();
          case RootSource.Argument arg ->
              arg.index() < ctx.arguments().length ? ctx.arguments()[arg.index()] : null;
          case RootSource.ReturnValue ignored -> ctx.returned();
        };
    if (rootObj == null) return null;
    if (segments.isEmpty()) return rootObj;
    return ctx.resolver().resolve(rootObj, segments);
  }
}