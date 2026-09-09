package org.otel.agent.bridge.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.otel.agent.bridge.runtime.RuntimeEngine;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Prepares the root classes used by the instrumentation matcher. */
final class ConfigurationRootScanner {
  private final RuntimeEngine runtime;

  ConfigurationRootScanner(final RuntimeEngine runtime) {
    this.runtime = runtime;
  }

  void prepare(final String xml, final ClassLoader loader) {
    try {
      runtime.prepareForInitialization(loader, scan(xml));
    } catch (final Throwable ignored) {
      // The full parser reports malformed configuration below.
    }
  }

  private static Map<String, Set<String>> scan(final String xml) throws ConfigurationException {
    final Element root =
        new ConfigurationParser().parseXmlDocument(xml.getBytes(StandardCharsets.UTF_8));
    final Element dynamic = childByName(root, "dynamic");
    if (dynamic == null) return Map.of();

    final Map<String, Set<String>> methodsByClass = new HashMap<>();
    for (final Element enrich : childrenByName(dynamic, "enrich")) {
      addMethod(methodsByClass, enrich);
    }
    return methodsByClass;
  }

  private static void addMethod(
      final Map<String, Set<String>> methodsByClass, final Element enrich) {
    final String className = enrich.getAttribute("class");
    final String methodName = enrich.getAttribute("method");
    if (className.isBlank() || methodName.isBlank()) return;
    methodsByClass.computeIfAbsent(className, ignored -> new HashSet<>()).add(methodName);
  }

  private static Element childByName(final Element parent, final String name) {
    for (final Element child : childrenByName(parent, null)) {
      if (name.equals(child.getTagName())) return child;
    }
    return null;
  }

  private static List<Element> childrenByName(final Element parent, final String name) {
    final List<Element> result = new ArrayList<>();
    final NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      final Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE
          && (name == null || name.equals(node.getNodeName()))) {
        result.add((Element) node);
      }
    }
    return result;
  }
}
