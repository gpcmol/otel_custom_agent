package org.otel.agent.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.EnrichmentRuntime;

/**
 * Regression test for the Metaspace leak on hot-redeploy that was caused by the bridge storing
 * {@link RuntimeState} <em>strongly</em> in a {@link java.util.WeakHashMap} value slot. A strong
 * value:
 *
 * <ol>
 *   <li>holds the {@link CompiledConfiguration} which holds {@code Class<?>} refs
 *   <li>{@code Class<?>} strongly references its declaring {@link ClassLoader}
 *   <li>the strong value→key cycle defeats {@link java.util.WeakHashMap}'s weak-key clearing
 * </ol>
 *
 * … so an application class loader once instrumented could never be unloaded.
 *
 * <p>After the fix, the bridge stores only {@link WeakReference}s to {@link RuntimeState}; the sole
 * strong holder is {@link EnrichmentRuntime#state()} (per application loader, dies with it). This
 * test asserts (1) the structural property and (2) the observable consequence: once the
 * EnrichmentRuntime strong holder releases a state, the bridge can no longer return it.
 */
class RuntimeBridgeMemoryLeakTest {
  private static final String TEST_MODEL_NAME =
      "org.otel.agent.bridge.RuntimeBridgeMemoryLeakTest$TestModel";

  private final ClassLoader loader = getClass().getClassLoader();

  @BeforeEach
  void setUp() {
    RuntimeBridge.resetForTesting();
    EnrichmentRuntime.setState(RuntimeState.disabled());
  }

  @AfterEach
  void tearDown() {
    RuntimeBridge.resetForTesting();
    EnrichmentRuntime.setState(RuntimeState.disabled());
  }

  @Test
  void bridgeStatesValuesAreWeakReferences() throws Exception {
    publish();
    final Map<?, ?> states = readStates();
    assertEquals(1, states.size(), "expected exactly one registered loader");
    final Object value = states.values().iterator().next();
    assertTrue(
        value instanceof WeakReference,
        "STATES value must be WeakReference<RuntimeState>, was: " + value.getClass().getName());
  }

  @Test
  void bridgeReleasesStateOnceEnrichmentRuntimeLetsGo() throws Exception {
    publish();
    assertTrue(RuntimeBridge.state(loader).enabled(), "state must be enabled after publish");

    // Drop the sole strong holder (the bridge sees only a WeakReference).
    EnrichmentRuntime.setState(RuntimeState.disabled());
    forceGc();

    assertFalse(
        RuntimeBridge.state(loader).enabled(),
        "bridge must not hold a strong RuntimeState: after the strong holder released, state() "
            + "should return DISABLED; if it returns enabled, the bridge is leaking "
            + "(regression of the Metaspace-on-hot-redeploy leak).");
  }

  private void publish() {
    try {
      final CompiledConfiguration config = new ConfigurationParser().parseXml(configXml(), loader);
      RuntimeBridge.publish(loader, config, configXml());
    } catch (final Exception exception) {
      throw new AssertionError("test config should be valid", exception);
    }
  }

  private static String configXml() {
    return "<configuration>"
        + "<static><attribute key=\"team\" value=\"leak\"/></static>"
        + "<dynamic><enrich class=\""
        + TEST_MODEL_NAME
        + "\" method=\"process\">"
        + "<attribute key=\"brand\" path=\"$this.brand\"/>"
        + "</enrich></dynamic>"
        + "</configuration>";
  }

  /** Reflection-only access to the private STATES field — keeps the test independent of getters. */
  @SuppressWarnings("unchecked")
  private static Map<?, ?> readStates() throws ReflectiveOperationException {
    final Field serviceField = RuntimeBridge.class.getDeclaredField("SERVICE");
    serviceField.setAccessible(true);
    final Object service = serviceField.get(null);
    final Class<?> serviceClass =
        Class.forName("org.otel.agent.bridge.config.RuntimeConfiguration");
    final Field runtimeField = serviceClass.getDeclaredField("runtime");
    runtimeField.setAccessible(true);
    final Object runtime = runtimeField.get(service);
    final Class<?> runtimeClass =
        Class.forName("org.otel.agent.bridge.runtime.RuntimeEngine");
    final Field storeField = runtimeClass.getDeclaredField("states");
    storeField.setAccessible(true);
    final Object store = storeField.get(runtime);
    final Class<?> storeClass = Class.forName("org.otel.agent.bridge.runtime.RuntimeStateStore");
    final Field statesField = storeClass.getDeclaredField("states");
    statesField.setAccessible(true);
    return (Map<?, ?>) statesField.get(store);
  }

  /** Best-effort GC: calls System.gc() several times until a canary WeakReference clears. */
  private static void forceGc() {
    final WeakReference<byte[]> canary = new WeakReference<>(new byte[1]);
    for (int round = 0; round < 30 && canary.get() != null; round++) {
      System.gc();
    }
    // Canary cleared confirms a GC actually ran; one extra pass sweeps WeakHashMap ReferenceQueue.
    System.gc();
  }

  public static final class TestModel {
    String brand = "leak";

    public void process() {}
  }
}
