package org.otel.agent.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttributeConverterTest {
  private final AttributeConverter converter = new AttributeConverter();

  @Test
  void supportsExtendedScalarTypes() {
    assertEquals("x", converter.convert('x').value());
    assertEquals(1L, converter.convert((byte) 1).value());
    assertEquals(2L, converter.convert((short) 2).value());
    assertEquals(3L, converter.convert(BigInteger.valueOf(3)).value());
    assertEquals(4D, converter.convert(BigDecimal.valueOf(4)).value());
  }

  @Test
  void rejectsMixedCollectionsAndOverflow() {
    assertNull(converter.convert(List.of("x", 1)));
    assertNull(converter.convert(BigInteger.ONE.shiftLeft(100)));
  }

  @Test
  void copiesPrimitiveArrays() {
    int[] values = {1, 2};
    var converted = converter.convert(values);
    values[0] = 9;
    assertEquals(List.of(1L, 2L), converted.value());
  }

  @Test
  void rejectsMixedTypeArrays() {
    assertNull(converter.convert(new Object[] {"x", 1}));
  }

  @Test
  void emptyArrayKeepsComponentType() {
    var converted = converter.convert(new int[0]);
    assertEquals(List.of(), converted.value());
    assertEquals(Long.class, converted.componentType());

    var emptyStrings = converter.convert(new String[0]);
    assertEquals(String.class, emptyStrings.componentType());
  }
}
