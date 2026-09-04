package org.otel.agent.bridge;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.ExitPointIndex;
import org.otel.agent.utils.Base64Util;
import org.otel.agent.webserver.ConfigWebserver;
import org.w3c.dom.Element;

/** Orchestrates startup, reload, publication, and the application-loader boundary. */
final class RuntimeConfigurationService {
  private final RuntimeStateStore states;
  private final ApplicationClassLoaderBridge applicationBridge;
  private final TtlScheduler ttlScheduler;
  private final AtomicBoolean webserverStarted = new AtomicBoolean();

  RuntimeConfigurationService() {
    states = new RuntimeStateStore();
    applicationBridge = new ApplicationClassLoaderBridge();
    ttlScheduler = new TtlScheduler(states, applicationBridge);
  }

  void publish(final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    final RuntimeState state =
        RuntimeState.enabled(configuration, new ExitPointIndex(configuration));
    states.publish(loader, state);
    applicationBridge.pushState(loader, state);
    ttlScheduler.schedule(loader, configuration.ttl());
    if (xml != null) states.setActiveXml(xml);
  }

  void initialize(final ClassLoader loader) {
    synchronized (states) {
      if (states.isEnabled(loader) && states.state(loader).enabled()) return;
    }

    final String encoded = System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    if (encoded == null) {
      states.disable(loader);
      return;
    }

    startWebserver();
    prepareRootMethods(encoded, loader);
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encoded, loader);
      publish(loader, configuration, decodeToXml(encoded));
      logConfiguration(configuration);
    } catch (final ConfigurationException exception) {
      states.disable(loader);
    }
  }

  ReloadResult reload(final String xml) {
    final List<ClassLoader> loaders = states.loaders();
    final List<String> failures = new ArrayList<>();
    int updated = 0;
    int staticCount = 0;
    int exitCount = 0;

    for (final ClassLoader loader : loaders) {
      try {
        final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
        if (!applicationBridge.reload(xml, loader)) {
          failures.add("reload failed in application classloader");
          continue;
        }
        updated++;
        staticCount = configuration.staticRules().size();
        exitCount = configuration.exitPoints().size();
      } catch (final ConfigurationException exception) {
        failures.add(category(exception));
      }
    }
    return new ReloadResult(updated, staticCount, exitCount, failures);
  }

  RuntimeState state(final ClassLoader loader) {
    return states.state(loader);
  }

  boolean enabled(final ClassLoader loader) {
    return states.isEnabled(loader);
  }

  Set<String> rootNames() {
    return states.rootNames();
  }

  Map<String, Set<String>> rootMethods() {
    return states.rootMethods();
  }

  String activeXml() {
    return states.activeXml();
  }

  void reset() {
    ConfigWebserver.stop();
    webserverStarted.set(false);
    ttlScheduler.reset();
    states.reset();
  }

  private void startWebserver() {
    if (!webserverStarted.compareAndSet(false, true)) return;
    try {
      ConfigWebserver.start();
    } catch (final Exception exception) {
      System.getLogger(RuntimeConfigurationService.class.getName())
          .log(System.Logger.Level.WARNING, "config webserver failed to start", exception);
    }
  }

  private void prepareRootMethods(final String encoded, final ClassLoader loader) {
    try {
      final String xml = decodeToXml(encoded);
      if (xml == null) return;
      final Map<String, Set<String>> methodsByClass = scanEnrichMethods(xml);
      states.prepareForInitialization(loader, methodsByClass);
    } catch (final Throwable ignored) {
      // The full parser reports malformed startup configuration below.
    }
  }

  private static Map<String, Set<String>> scanEnrichMethods(final String xml)
      throws ConfigurationException {
    final Element root =
        new ConfigurationParser().parseXmlDocument(xml.getBytes(StandardCharsets.UTF_8));
    final Element dynamic = childByName(root, "dynamic");
    if (dynamic == null) return Map.of();
    final Map<String, Set<String>> methodsByClass = new HashMap<>();
    for (final Element enrich : childrenByName(dynamic, "enrich")) {
      final String className = enrich.getAttribute("class");
      final String methodName = enrich.getAttribute("method");
      if (className.isBlank() || methodName.isBlank()) continue;
      methodsByClass.computeIfAbsent(className, ignored -> new HashSet<>()).add(methodName);
    }
    return methodsByClass;
  }

  private static Element childByName(final Element parent, final String name) {
    for (final Element child : childrenByName(parent, null)) {
      if (name.equals(child.getTagName())) return child;
    }
    return null;
  }

  private static List<Element> childrenByName(final Element parent, final String name) {
    final List<Element> result = new ArrayList<>();
    final org.w3c.dom.NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final org.w3c.dom.Node node = nodes.item(i);
      if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
          && (name == null || name.equals(node.getNodeName()))) {
        result.add((Element) node);
      }
    }
    return result;
  }

  private static String decodeToXml(final String encoded) {
    try {
      return new String(Base64Util.decode(encoded), StandardCharsets.UTF_8);
    } catch (final ConfigurationException exception) {
      return null;
    }
  }

  private static String category(final ConfigurationException exception) {
    final String message = exception.getMessage();
    final int colon = message.indexOf(':');
    return colon < 0 ? message : message.substring(0, colon);
  }

  private static void logConfiguration(final CompiledConfiguration configuration) {
    System.getLogger(RuntimeConfigurationService.class.getName())
        .log(
            System.Logger.Level.INFO,
            "enabled static={0} exit-points={1} roots={2}",
            configuration.staticRules().size(),
            configuration.exitPoints().size(),
            configuration.exitPoints().stream().map(ExitPoint::rootClass).distinct().count());
  }
}
