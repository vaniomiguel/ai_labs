package pt.conta.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import pt.conta.shared.Json;

/** Validates the deliberately small JSON Schema vocabulary used by the bundled task schemas. */
public final class StructuredOutput {
  private StructuredOutput() {}

  /**
   * Rejects malformed types, unknown properties, missing required fields and invalid enum values.
   */
  public static Map<String, Object> parse(String output, Map<String, Object> schema) {
    JsonNode value = Json.read(output);
    validate(value, Json.read(Json.write(schema)), "$");
    return Json.object(output);
  }

  private static void validate(JsonNode value, JsonNode schema, String path) {
    if (schema.has("enum")) {
      boolean found = false;
      for (var option : schema.path("enum")) if (option.equals(value)) found = true;
      if (!found) throw new IllegalArgumentException("Valor fora do esquema: " + path);
    }
    if (schema.has("type")) {
      var types = schema.get("type");
      boolean matches = false;
      if (types.isArray()) {
        for (var type : types) if (matches(value, type.asText())) matches = true;
      } else matches = matches(value, types.asText());
      if (!matches) throw new IllegalArgumentException("Tipo inválido na resposta IA: " + path);
    }
    if (value.isObject()) {
      for (var required : schema.path("required"))
        if (!value.has(required.asText()))
          throw new IllegalArgumentException(
              "Campo em falta na resposta IA: " + path + "." + required.asText());
      var fields = value.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        var definition = schema.path("properties").get(field.getKey());
        if (definition == null
            && schema.has("additionalProperties")
            && !schema.get("additionalProperties").asBoolean())
          throw new IllegalArgumentException("Campo desconhecido: " + path + "." + field.getKey());
        if (definition != null) validate(field.getValue(), definition, path + "." + field.getKey());
      }
    }
    if (value.isArray() && schema.has("items"))
      for (var item : value) validate(item, schema.get("items"), path + "[]");
  }

  private static boolean matches(JsonNode value, String type) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> throw new IllegalArgumentException("Tipo de esquema não suportado: " + type);
    };
  }
}
