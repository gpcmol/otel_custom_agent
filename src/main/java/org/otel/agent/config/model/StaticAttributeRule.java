package org.otel.agent.config.model;

/**
 * A constant span attribute applied to every span when this configuration is active.
 *
 * <p>Used at runtime by {@link org.otel.agent.runtime.EnrichmentRuntime#write}, which caches
 * pre-built {@code key → AttributeValue} arrays keyed on the current {@link org.otel.agent.bridge.RuntimeState}
 * identity. The cache is rebuilt only when the active configuration changes, so steady-state
 * enrichment incurs zero allocations for static rules.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code key} — OTel attribute key (max 255 chars, validated for uniqueness at parse time)</li>
 *   <li>{@code value} — constant string value written verbatim to every matching span</li>
 * </ul>
 */
public record StaticAttributeRule(String key, String value) {}
