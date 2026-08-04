package org.otel.agent.config.parser;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.ExitPoint;
import org.otel.agent.config.model.ExitRule;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PathSegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.config.model.RootSource;
import org.otel.agent.config.model.StaticAttributeRule;
import org.otel.agent.expr.Condition;
import org.otel.agent.expr.ExpressionCompileException;
import org.otel.agent.expr.parser.ExpressionParser;
import org.otel.agent.utils.Base64Util;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parses XML configuration into an immutable {@link CompiledConfiguration}.
 *
 * <p>Two entry points: {@link #parse(String, ClassLoader)} for Base64-encoded startup config
 * (read from the {@code OTEL_CUSTOM_AGENT_CONFIG} environment variable), and
 * {@link #parseXml(String, ClassLoader)} for raw XML submitted at runtime via the config
 * webserver. Both paths share the same secure XML parsing pipeline and validation rules.
 *
 * <p>XML is parsed with {@link DocumentBuilderFactory} configured for secure processing:
 * external entities, DTDs, and external schema resolution are all disabled to prevent XXE
 * attacks. The document root must be a namespace-less {@code <configuration>} element with
 * optional {@code <static>} and {@code <dynamic>} sections.
 *
 * <p>Dynamic rules are grouped into {@code <enrich class method>} blocks; one block per
 * declared exit-point method. Each block contains {@code <attribute key path>} children whose
 * {@code path} begins with a root-source prefix — {@code $this}, {@code $argN} (decimal
 * non-negative index), or {@code $return} — followed by dot-separated property segments using
 * the existing {@link PathSegment} grammar (e.g. {@code $arg0.passengers[1].name}). The
 * {@code <enrich class>} value MUST be a fully qualified Java class name resolvable in the
 * configured application classloader; {@code <enrich method>} MUST be a Java identifier naming
 * at least one method on that class (any parameter arity). Duplicate {@code (class, method)}
 * pairs are rejected.
 *
 * <p>All validation errors are wrapped in {@link ConfigurationException} so callers can
 * distinguish parse failures from successful compilation.
 */
public final class ConfigurationParser {
  public CompiledConfiguration parse(final String encoded, final ClassLoader applicationLoader)
      throws ConfigurationException {
    if (encoded == null) {
      return new CompiledConfiguration(List.of(), List.of());
    }
    if (encoded.trim().isEmpty()) {
      throw new ConfigurationException("configuration is blank");
    }
    return compile(parseXmlDocument(Base64Util.decode(encoded)), applicationLoader);
  }

  public CompiledConfiguration parseXml(final String xml, final ClassLoader loader)
      throws ConfigurationException {
    if (xml == null || xml.trim().isEmpty()) {
      throw new ConfigurationException("configuration is blank");
    }
    return compile(parseXmlDocument(xml.getBytes(StandardCharsets.UTF_8)), loader);
  }

  private CompiledConfiguration compile(final Element root, final ClassLoader loader)
      throws ConfigurationException {
    validateElement(root);
    final Set<String> keys = new HashSet<>();
    final List<StaticAttributeRule> staticRules = parseStatic(root, keys);
    final List<ExitPoint> exitPoints = parseDynamic(root, keys, loader);
    return new CompiledConfiguration(staticRules, exitPoints);
  }

  public Element parseXmlDocument(final byte[] bytes) throws ConfigurationException {
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

  private List<ExitPoint> parseDynamic(
      final Element root, final Set<String> keys, final ClassLoader applicationLoader)
      throws ConfigurationException {
    final Element section = child(root, "dynamic");
    if (section == null) return List.of();
    if (!children(section, "attribute").isEmpty()) {
      throw new ConfigurationException(
          "dynamic section requires <enrich class method> blocks; the flat <attribute path=...> "
              + "form is no longer supported");
    }
    validateChildren(section, "enrich");
    final Set<String> seenExitPoints = new HashSet<>();
    final List<ExitPoint> exitPoints = new ArrayList<>();
    for (final Element enrichElement : children(section, "enrich")) {
      validateAttributes(enrichElement, Set.of("class", "method", "expr"));
      final String className = required(enrichElement, "class");
      final String methodName = required(enrichElement, "method");
      validateIdentifier(methodName);
      final String exitKey = className + "#" + methodName;
      if (!seenExitPoints.add(exitKey)) {
        throw new ConfigurationException("duplicate exit point: " + exitKey);
      }
      final Class<?> rootClass;
      try {
        rootClass = Class.forName(className, false, applicationLoader);
      } catch (final ClassNotFoundException e) {
        throw new ConfigurationException("exit-point class cannot be resolved: " + className);
      }
      final Method exitMethod = verifyMethodExists(rootClass, methodName, className);
      validateChildren(enrichElement, "attribute");
      final List<ExitRule> rules = new ArrayList<>();
      for (final Element attribute : children(enrichElement, "attribute")) {
        validateAttributes(attribute, Set.of("key", "path"));
        final String key = required(attribute, "key");
        final String path = required(attribute, "path");
        validateKey(key, keys);
        rules.add(parseExitRule(key, path));
      }
      final String expr = enrichElement.getAttribute("expr");
      final Condition condition = compileExpr(expr, exitKey, rootClass, exitMethod);
      exitPoints.add(new ExitPoint(rootClass, className, methodName, rules, condition));
    }
    return exitPoints;
  }

  private Method verifyMethodExists(
      final Class<?> type, final String methodName, final String className)
      throws ConfigurationException {
    for (final Method method : type.getMethods()) {
      if (method.getName().equals(methodName)) return method;
    }
    throw new ConfigurationException(
        "exit-point method not found: " + className + "#" + methodName);
  }

  private Condition compileExpr(final String expr, final String blockId,
      final Class<?> rootClass, final Method exitMethod) {
    if (expr == null || expr.isBlank()) return null;
    try {
      return new ExpressionParser().parse(expr, blockId, rootClass, exitMethod);
    } catch (final ExpressionCompileException e) {
      System.getLogger(ConfigurationParser.class.getName())
          .log(System.Logger.Level.WARNING,
              "expression disabled for " + e.blockId() + ": " + e.category());
      return null;
    }
  }

  private ExitRule parseExitRule(final String key, final String path)
      throws ConfigurationException {
    final int dot = path.indexOf('.');
    if (dot < 0) throw new ConfigurationException("path has no root or property");
    final RootSource rootSource = parseRootSource(path.substring(0, dot));
    final String rest = path.substring(dot + 1);
    if (rest.isEmpty()) throw new ConfigurationException("path has no property");
    final String[] parts = rest.split("\\.", -1);
    final List<PathSegment> segments = new ArrayList<>();
    for (final String part : parts) segments.add(parseSegment(part));
    return new ExitRule(key, rootSource, segments);
  }

  private RootSource parseRootSource(final String token) throws ConfigurationException {
    if ("$this".equals(token)) return new RootSource.This();
    if ("$return".equals(token)) return new RootSource.ReturnValue();
    if (token.startsWith("$arg")) {
      final String digits = token.substring(4);
      if (digits.isEmpty()) throw new ConfigurationException("$arg requires a non-negative index");
      final int index;
      try {
        index = Integer.parseInt(digits);
      } catch (final NumberFormatException e) {
        throw new ConfigurationException("invalid $arg index: " + token);
      }
      if (index < 0 || index > 127) {
        throw new ConfigurationException("$arg index out of range (0..127): " + token);
      }
      return new RootSource.Argument(index);
    }
    throw new ConfigurationException("unknown root source: " + token);
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

  private void validateElement(final Element element) throws ConfigurationException {
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
