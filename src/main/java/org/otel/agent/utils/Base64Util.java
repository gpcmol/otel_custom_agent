package org.otel.agent.utils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.otel.agent.config.parser.ConfigurationException;

/**
 * Decodes Base64-encoded configuration strings with strict UTF-8 validation.
 *
 * <p>Used by {@link org.otel.agent.config.parser.ConfigurationParser} for startup config
 * (from the {@code OTEL_CUSTOM_AGENT_CONFIG} environment variable), by
 * {@link org.otel.agent.bridge.RuntimeBridge} to recover the original XML for display, and by
 * {@link org.otel.agent.webserver.ConfigWebserver} to serve the original config via HTTP.
 *
 * <p>The decode is strict: invalid Base64 throws {@link ConfigurationException("invalid Base64")},
 * and bytes that are not valid UTF-8 throw {@link ConfigurationException("invalid UTF-8")}.
 */
public final class Base64Util {
  private Base64Util() {}

  public static byte[] decode(final String encoded) throws ConfigurationException {
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
}
