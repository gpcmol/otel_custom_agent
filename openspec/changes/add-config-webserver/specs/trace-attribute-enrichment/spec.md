## MODIFIED Requirements

### Requirement: The implementation SHALL keep package responsibilities separate

The implementation MUST use separate packages for configuration parsing and
model, runtime model, resolver, accessor, cache, instrumentation, telemetry,
the minimal bridge, and the webserver. No package may own both XML parsing
and runtime traversal. Interfaces with one implementation MUST NOT be added
unless required by Byte Buddy, testing, or a classloader boundary.

The package responsibilities SHOULD map to this layout, adapting the root
package name to the project:

```text
agent/
  config/parser/       Base64, secure XML, validation, path compilation
  config/model/        Immutable parsed/compiled configuration types
  runtime/model/       Rules, segments, immutable indexes
  runtime/resolver/    Segment execution and value conversion
  runtime/accessor/    Getter/field accessor abstraction
  runtime/cache/       Accessor and runtime-class lookup caches
  instrumentation/     InstrumentationModule, TypeInstrumentation, Advice
  telemetry/           Span/tracer access and typed attribute writing
  bridge/              Minimal application-to-agent runtime bridge and reload entry point
  webserver/           Embedded HTTP server, HTML UI, and reload handler
```

The implementation MUST NOT add map traversal, static method/field traversal,
constructor/field-write/arbitrary-method instrumentation, multiple
configuration sources or merging, transformations, filtering, conditionals,
aggregation, custom user code, runtime path parsing, regex matching,
string-based property traversal, or normal runtime logging.

Runtime configuration reload via the embedded webserver on port `14317` is
permitted: the webserver package accepts raw XML, delegates compilation to
the configuration parser, and delegates publication to the runtime bridge.
The reload MUST reuse the existing immutable compilation pipeline and MUST
NOT introduce a second configuration format, merging, or diffing.

Future boundaries MUST remain explicit: map/key segments, filters,
transformations, custom converters, additional instrumentation points, and
new segment types MUST NOT be implemented in this change. Any future segment
type MUST be added behind the existing `PathSegment` model and MUST NOT
reintroduce runtime string parsing.

#### Scenario: Out-of-scope behavior is not introduced

- **WHEN** the extension is implemented
- **THEN** it MUST provide only the configured instance-property enrichment
  behavior and the webserver-driven runtime reload defined by this
  specification
- **AND** out-of-scope traversal, transformation, and instrumentation
  features MUST NOT be added

#### Scenario: Runtime reload reuses the compilation pipeline

- **WHEN** the webserver receives a reload request with valid XML
- **THEN** it MUST delegate to `ConfigurationParser` and `RuntimeBridge`
- **AND** it MUST NOT duplicate parsing, validation, or rule-index logic
- **AND** no second configuration format or merging MUST be introduced
> Status: superseded. The embedded configuration webserver and the Base64
> environment configuration were removed in favor of XML file configuration.
