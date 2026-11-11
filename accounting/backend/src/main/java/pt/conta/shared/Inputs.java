package pt.conta.shared;

import java.time.*;
import java.util.*;

/** Strict conversion of editable API and extraction drafts to domain values. */
public final class Inputs {
  private Inputs() {}

  /** Returns optional text without manufacturing placeholder data. */
  public static String text(Map<String, ?> map, String key) {
    var v = map.get(key);
    return v == null || v.toString().isBlank() ? null : v.toString().trim();
  }

  /** Reads a date in the API's unambiguous ISO format. */
  public static LocalDate date(Map<String, ?> map, String key) {
    String s = text(map, key);
    return s == null ? null : LocalDate.parse(s);
  }

  /** Reads an optional source identity. */
  public static UUID uuid(Map<String, ?> map, String key) {
    String s = text(map, key);
    return s == null ? null : UUID.fromString(s);
  }

  /** Reads a named enum with an explicit default. */
  public static <T extends Enum<T>> T enumeration(
      Map<String, ?> map, String key, Class<T> type, T fallback) {
    String s = text(map, key);
    return s == null ? fallback : Enum.valueOf(type, s);
  }

  /** Returns a boolean flag without accepting truthy strings accidentally. */
  public static boolean flag(Map<String, ?> map, String key) {
    return Boolean.TRUE.equals(map.get(key));
  }

  /** Reads nested objects used by editable line and movement tables. */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> rows(Map<String, ?> map, String key) {
    Object rows = map.get(key);
    return rows instanceof List<?> list
        ? list.stream().map(v -> (Map<String, Object>) v).toList()
        : List.of();
  }

  /** Reads a required optimistic version on updates. */
  public static long version(Map<String, ?> map) {
    if (!map.containsKey("version"))
      throw new DomainException(428, "A versão do registo é obrigatória");
    return Long.parseLong(map.get("version").toString());
  }

  /** Builds a response map that permits explicit null values for unknown fields. */
  public static Map<String, Object> map(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
    return map;
  }
}
