package pt.conta.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Explicit JSON codec for persisted extraction evidence and provider-neutral AI payloads. */
public final class Json {
  private static final ObjectMapper MAPPER = mapper();

  private static ObjectMapper mapper() {
    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
    module.addSerializer(
        java.time.temporal.TemporalAccessor.class,
        com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
    return new ObjectMapper().findAndRegisterModules().registerModule(module);
  }

  private Json() {}

  /** Serializes an audit-safe value without losing decimal precision. */
  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Não foi possível guardar o resultado JSON", e);
    }
  }

  /** Reads JSON, rejecting malformed output rather than guessing a correction. */
  public static JsonNode read(String value) {
    try {
      return MAPPER.readTree(value == null || value.isBlank() ? "{}" : value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON inválido", e);
    }
  }

  /** Converts a JSON object to an editable map. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(String value) {
    return MAPPER.convertValue(read(value), Map.class);
  }

  /** Returns plain Java JSON values, avoiding Jackson-version-specific nodes at HTTP boundaries. */
  public static Object value(String value) {
    return MAPPER.convertValue(read(value), Object.class);
  }
}
