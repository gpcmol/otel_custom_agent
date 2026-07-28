package org.otel.agent.config.model;

public record IndexedPropertySegment(String propertyName, int index) implements PathSegment {}
