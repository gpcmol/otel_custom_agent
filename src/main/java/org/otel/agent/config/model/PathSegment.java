package org.otel.agent.config.model;

public sealed interface PathSegment permits PropertySegment, IndexedPropertySegment {}
