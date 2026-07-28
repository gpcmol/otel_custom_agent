package org.otel.agent.runtime.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.runtime.accessor.AccessorCache;

class ValueResolverTest {
  private final ValueResolver resolver = new ValueResolver(new AccessorCache());

  @Test
  void getterWinsOverField() {
    assertEquals("getter", resolver.resolve(new Model(), List.of(new PropertySegment("brand"))));
  }

  @Test
  void nestedCollectionPreservesOrder() {
    var value =
        resolver.resolve(
            new Model(), List.of(new PropertySegment("passengers"), new PropertySegment("name")));
    assertEquals(List.of("A", "B"), value);
  }

  @Test
  void indexedIterableReturnsRequestedValue() {
    assertEquals(
        "B",
        resolver.resolve(
            new Model(),
            List.of(new IndexedPropertySegment("passengers", 1), new PropertySegment("name"))));
  }

  @Test
  void inaccessibleValueIsSkipped() {
    assertNull(resolver.resolve(new PrivateModel(), List.of(new PropertySegment("value"))));
  }

  public static final class Model {
    public String brand = "field";
    public List<Passenger> passengers = List.of(new Passenger("A"), new Passenger("B"));

    public String getBrand() {
      return brand.equals("field") ? "getter" : brand;
    }

    public List<Passenger> getPassengers() {
      return passengers;
    }
  }

  public static final class Passenger {
    private final String name;

    Passenger(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  private static final class PrivateModel {
    private String getValue() {
      return "hidden";
    }
  }
}
