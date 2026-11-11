package pt.conta.ai;

import java.util.*;
import java.util.regex.*;
import pt.conta.document.PortugueseDocumentParser;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.*;

/** Verifies quoted evidence against source text instead of trusting model self-confidence. */
public final class AiEvidence {
  private AiEvidence() {}

  /** Requires actual text evidence for each critical invoice field before automatic publication. */
  public static boolean supportsInvoice(
      Map<String, Object> draft, List<Map<String, Object>> evidence, String source) {
    for (String field : List.of("issuedOn", "supplier", "total")) {
      Object value = draft.get(field);
      if (value == null) return false;
      boolean supported =
          evidence.stream()
              .anyMatch(
                  e ->
                      field.equals(e.get("field"))
                          && quoteExists(e, source)
                          && supports(
                              field, value.toString(), Objects.toString(e.get("quote"), "")));
      if (!supported) return false;
    }
    var lines = Inputs.rows(draft, "lines");
    for (int n = 0; n < lines.size(); n++) {
      int index = n;
      var line = lines.get(n);
      if (line.get("total") == null
          || !evidence.stream()
              .anyMatch(
                  e ->
                      ("lines[" + index + "].total").equals(e.get("field"))
                          && quoteExists(e, source)
                          && amountIn(
                              line.get("total").toString(), Objects.toString(e.get("quote"), ""))))
        return false;
    }
    return true;
  }

  /** Requires evidence for movement date, description, amount and direction only. */
  public static boolean supportsStatement(
      Map<String, Object> draft, List<Map<String, Object>> evidence, String source) {
    var movements = Inputs.rows(draft, "movements");
    if (movements.isEmpty()) return false;
    for (int n = 0; n < movements.size(); n++) {
      int index = n;
      var movement = movements.get(n);
      for (String field : List.of("bookedOn", "description", "amount", "direction"))
        if (movement.get(field) == null
            || !evidence.stream()
                .anyMatch(
                    e ->
                        ("movements[" + index + "]." + field).equals(e.get("field"))
                            && quoteExists(e, source)
                            && bankValueSupported(
                                field, movement, Objects.toString(e.get("quote"), ""))))
          return false;
    }
    return true;
  }

  /** Keeps bank-review concerns within movement facts, excluding all balance fields. */
  public static boolean bankFieldRelevant(String field) {
    String key = MatchingRules.normalize(field).replaceAll("[^A-Z]", "");
    if (key.contains("BALANCE") || key.contains("SALDO")) return false;
    return key.equals("MOVEMENTS")
        || key.equals("MOVIMENTOS")
        || key.endsWith("BOOKEDON")
        || key.endsWith("DESCRIPTION")
        || key.endsWith("AMOUNT")
        || key.endsWith("DIRECTION")
        || Set.of("DATE", "DATA", "DESCRICAO", "MONTANTE", "VALOR", "DIRECAO").contains(key);
  }

  /** Requires the direction label or the same signed amount; dates are never treated as signs. */
  private static boolean bankValueSupported(
      String field, Map<String, Object> movement, String quote) {
    String value = movement.get(field).toString();
    if (field.equals("bookedOn")) return supports("issuedOn", value, quote);
    if (field.equals("description"))
      return MatchingRules.normalize(quote).contains(MatchingRules.normalize(value));
    if (field.equals("amount")) return amountIn(value, quote);
    String normalized = MatchingRules.normalize(quote);
    if (value.equals("DEBIT") && normalized.contains("DEBIT")) return true;
    if (value.equals("CREDIT") && normalized.contains("CREDIT")) return true;
    var sign =
        Pattern.compile("([+-])\\s*((?:[0-9]{1,3}(?:[. ][0-9]{3})+|[0-9]+)[,.][0-9]{2})")
            .matcher(quote);
    while (sign.find())
      if (amountIn(movement.get("amount").toString(), sign.group(2))
          && (value.equals("DEBIT") && sign.group(1).equals("-")
              || value.equals("CREDIT") && sign.group(1).equals("+"))) return true;
    return false;
  }

  private static boolean amountIn(String value, String quote) {
    var matcher =
        Pattern.compile("[+-]?(?:[0-9]{1,3}(?:[. ][0-9]{3})+|[0-9]+)[,.][0-9]{2}").matcher(quote);
    while (matcher.find())
      try {
        if (Money.optional(value)
                .compareTo(
                    Money.optional(
                        PortugueseDocumentParser.amount(matcher.group().replaceFirst("^[+-]", ""))))
            == 0) return true;
      } catch (Exception ignored) {
      }
    return false;
  }

  /** Accepts only a verbatim or whitespace-normalized source quote of meaningful length. */
  public static boolean quoteExists(Map<String, Object> concern, String source) {
    String quote = Objects.toString(concern.get("quote"), "").replaceAll("\\s+", " ").trim();
    return quote.length() >= 5 && source.replaceAll("\\s+", " ").contains(quote);
  }

  private static boolean supports(String field, String value, String quote) {
    if (field.equals("supplier"))
      return MatchingRules.normalize(quote).contains(MatchingRules.normalize(value));
    if (field.equals("total")) {
      if (!MatchingRules.normalize(quote).matches(".*(?:TOTAL|PAGAR).*")) return false;
      var matcher =
          Pattern.compile("(?:[0-9]{1,3}(?:[. ][0-9]{3})+|[0-9]+)[,.][0-9]{2}").matcher(quote);
      while (matcher.find())
        try {
          if (Money.optional(value)
                  .compareTo(Money.optional(PortugueseDocumentParser.amount(matcher.group())))
              == 0) return true;
        } catch (Exception ignored) {
        }
      return false;
    }
    var matcher =
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}").matcher(quote);
    while (matcher.find())
      try {
        if (PortugueseDocumentParser.date(matcher.group(), null).toString().equals(value))
          return true;
      } catch (Exception ignored) {
      }
    return false;
  }
}
