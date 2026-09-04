package org.otel.agent.bridge;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Schedules per-loader expiry without retaining application classloaders. */
final class TtlScheduler {
  private final RuntimeStateStore states;
  private final ApplicationClassLoaderBridge applicationBridge;
  private final Map<ClassLoader, Expiry> expiries = new WeakHashMap<>();
  private ScheduledExecutorService executor;

  TtlScheduler(
      final RuntimeStateStore states, final ApplicationClassLoaderBridge applicationBridge) {
    this.states = states;
    this.applicationBridge = applicationBridge;
  }

  synchronized void schedule(final ClassLoader loader, final Duration ttl) {
    final Expiry previous = expiries.remove(loader);
    if (previous != null) previous.cancel();

    final Expiry expiry = new Expiry(loader);
    expiry.future = executor().schedule(expiry::expire, ttl.toNanos(), TimeUnit.NANOSECONDS);
    expiries.put(loader, expiry);
  }

  synchronized void reset() {
    for (final Expiry expiry : expiries.values()) expiry.cancel();
    expiries.clear();
  }

  private ScheduledExecutorService executor() {
    if (executor == null) {
      executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "otel-custom-agent-ttl");
        thread.setDaemon(true);
        return thread;
      });
    }
    return executor;
  }

  private final class Expiry {
    private final WeakReference<ClassLoader> loader;
    private ScheduledFuture<?> future;

    private Expiry(final ClassLoader loader) {
      this.loader = new WeakReference<>(loader);
    }

    private void cancel() {
      if (future != null) future.cancel(false);
    }

    private void expire() {
      final ClassLoader applicationLoader = loader.get();
      if (applicationLoader == null) return;
      synchronized (TtlScheduler.this) {
        if (expiries.get(applicationLoader) != this) return;
        expiries.remove(applicationLoader);
        states.disable(applicationLoader);
        applicationBridge.pushState(applicationLoader, RuntimeState.disabled());
        System.getLogger(TtlScheduler.class.getName())
            .log(System.Logger.Level.INFO, "configuration TTL expired, instrumentation disabled");
      }
    }
  }
}
