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

  /**
   * Requires control balances and explicit source evidence for every bank row before AI
   * publication.
   */
  public static boolean supportsStatement(
      Map<String, Object> draft, List<Map<String, Object>> evidence, String source) {
    for (String field : List.of("openingBalance", "closingBalance")) {
      if (draft.get(field) == null
          || !evidence.stream()
              .anyMatch(
                  e ->
                      field.equals(e.get("field"))
                          && quoteExists(e, source)
                          && amountIn(
                              draft.get(field).toString(), Objects.toString(e.get("quote"), ""))))
        return false;
    }
    var movements = Inputs.rows(draft, "movements");
    if (movements.isEmpty()) return false;
    for (int n = 0; n < movements.size(); n++) {
      int index = n;
      var movement = movements.get(n);
      if (movement.get("amount") == null || movement.get("direction") == null) return false;
      if (!evidence.stream()
          .anyMatch(
              e ->
                  ("movements[" + index + "].amount").equals(e.get("field"))
                      && quoteExists(e, source)
                      && amountIn(
                          movement.get("amount").toString(), Objects.toString(e.get("quote"), ""))))
        return false;
    }
    return true;
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
