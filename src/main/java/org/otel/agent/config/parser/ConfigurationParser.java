package org.otel.agent.config.parser;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PathSegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.StaticAttributeRule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class ConfigurationParser {
  public CompiledConfiguration parse(final String encoded, final ClassLoader applicationLoader)
      throws ConfigurationException {
    if (encoded == null) {
      return new CompiledConfiguration(List.of(), List.of());
    }
    if (encoded.trim().isEmpty()) {
      throw new ConfigurationException("configuration is blank");
    }

    final Element root = parseXml(decode(encoded));
    validateElement(root);
    final Set<String> keys = new HashSet<>();
    final List<StaticAttributeRule> staticRules = parseStatic(root, keys);
    final List<DynamicAttributeRule> dynamicRules = parseDynamic(root, keys, applicationLoader);
    return new CompiledConfiguration(staticRules, dynamicRules);
  }

  private byte[] decode(final String encoded) throws ConfigurationException {
    try {
      final byte[] bytes = Base64.getDecoder().decode(encoded.trim());
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
      return bytes;
    } catch (final CharacterCodingException e) {
      throw new ConfigurationException("invalid UTF-8", e);
    } catch (final IllegalArgumentException e) {
      throw new ConfigurationException("invalid Base64", e);
    }
  }

  private Element parseXml(final byte[] bytes) throws ConfigurationException {
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      final DocumentBuilder builder = factory.newDocumentBuilder();
      try (final ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
        final Document document = builder.parse(new InputSource(input));
        return document.getDocumentElement();
      }
    } catch (final Exception e) {
      throw new ConfigurationException("invalid XML", e);
    }
  }

  private List<StaticAttributeRule> parseStatic(final Element root, final Set<String> keys)
      throws ConfigurationException {
    final Element section = child(root, "static");
    if (section == null) return List.of();
    validateChildren(section, "attribute");
    final List<StaticAttributeRule> rules = new ArrayList<>();
    for (final Element element : children(section, "attribute")) {
      validateAttributes(element, Set.of("key", "value"));
      final String key = required(element, "key");
      final String value = required(element, "value");
      validateKey(key, keys);
      rules.add(new StaticAttributeRule(key, value));
    }
    return rules;
  }

  private List<DynamicAttributeRule> parseDynamic(
      final Element root, final Set<String> keys, final ClassLoader applicationLoader)
      throws ConfigurationException {
    final Element section = child(root, "dynamic");
    if (section == null) return List.of();
    validateChildren(section, "attribute");
    final List<DynamicAttributeRule> rules = new ArrayList<>();
    for (final Element element : children(section, "attribute")) {
      validateAttributes(element, Set.of("key", "path"));
      final String key = required(element, "key");
      final String path = required(element, "path");
      validateKey(key, keys);
      rules.add(parseRule(key, path, applicationLoader));
    }
    return rules;
  }

  private DynamicAttributeRule parseRule(
      final String key, final String path, final ClassLoader loader) throws ConfigurationException {
    final String[] parts = path.split("\\.", -1);
    if (parts.length < 2) throw new ConfigurationException("path has no root or property");
    for (int boundary = parts.length - 1; boundary > 0; boundary--) {
      final String className = String.join(".", Arrays.copyOf(parts, boundary));
      try {
        final Class<?> rootClass = Class.forName(className, false, loader);
        final List<PathSegment> segments = new ArrayList<>();
        for (int i = boundary; i < parts.length; i++) segments.add(parseSegment(parts[i]));
        if (segments.isEmpty()) throw new ConfigurationException("path has no property");
        return new DynamicAttributeRule(key, className, rootClass, segments);
      } catch (final ClassNotFoundException ignored) {
        // Try the next shorter class-name prefix.
      }
    }
    throw new ConfigurationException("root class cannot be resolved");
  }

  private PathSegment parseSegment(final String value) throws ConfigurationException {
    if (value.isEmpty()) throw new ConfigurationException("empty path segment");
    final int bracket = value.indexOf('[');
    if (bracket < 0) {
      validateIdentifier(value);
      return new PropertySegment(value);
    }
    if (!value.endsWith("]") || value.indexOf('[', bracket + 1) >= 0) {
      throw new ConfigurationException("malformed indexed segment");
    }
    final String name = value.substring(0, bracket);
    final String indexText = value.substring(bracket + 1, value.length() - 1);
    validateIdentifier(name);
    if (indexText.isEmpty() || !indexText.chars().allMatch(Character::isDigit)) {
      throw new ConfigurationException("invalid index");
    }
    try {
      return new IndexedPropertySegment(name, Integer.parseInt(indexText));
    } catch (final NumberFormatException e) {
      throw new ConfigurationException("index is out of range", e);
    }
  }

  private void validateIdentifier(final String value) throws ConfigurationException {
    if (value.isEmpty()
        || !Character.isJavaIdentifierStart(value.charAt(0))
        || !value.substring(1).chars().allMatch(Character::isJavaIdentifierPart)) {
      throw new ConfigurationException("invalid path identifier");
    }
  }

  private void validateElement(final Element element)
      throws ConfigurationException {
    if (!"configuration".equals(element.getTagName()) || element.getNamespaceURI() != null) {
      throw new ConfigurationException("root element must be configuration without namespace");
    }
    validateAttributes(element, Set.of());
    validateChildren(element, "static", "dynamic");
  }

  private void validateChildren(final Element parent, final String... allowed)
      throws ConfigurationException {
    final Set<String> names = Set.of(allowed);
    for (final Element child : children(parent, null)) {
      if (!names.contains(child.getTagName()) || child.getNamespaceURI() != null) {
        throw new ConfigurationException("unknown XML element: " + child.getTagName());
      }
    }
  }

  private void validateAttributes(final Element element, final Set<String> allowed)
      throws ConfigurationException {
    for (int i = 0; i < element.getAttributes().getLength(); i++) {
      final Node attribute = element.getAttributes().item(i);
      if (!allowed.contains(attribute.getNodeName()) || attribute.getNamespaceURI() != null) {
        throw new ConfigurationException("unknown XML attribute: " + attribute.getNodeName());
      }
    }
  }

  private String required(final Element element, final String name) throws ConfigurationException {
    final String value = element.getAttribute(name);
    if (value.isBlank()) throw new ConfigurationException("missing attribute: " + name);
    return value;
  }

  private void validateKey(final String key, final Set<String> keys) throws ConfigurationException {
    if (key.length() > 255 || !keys.add(key))
      throw new ConfigurationException("invalid or duplicate key");
  }

  private Element child(final Element parent, final String name) throws ConfigurationException {
    final List<Element> matches = children(parent, name);
    if (matches.size() > 1) throw new ConfigurationException("duplicate section: " + name);
    return matches.isEmpty() ? null : matches.get(0);
  }

  private List<Element> children(final Element parent, final String name) {
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
