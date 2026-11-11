package pt.conta.document;

import java.math.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.*;

/**
 * Preserves valid extracted fields when a different field cannot be converted to its domain type.
 */
public final class DraftSanitizer {
  private DraftSanitizer() {}

  /**
   * Copies an automatic extraction, blanks only malformed typed fields and records review reasons.
   */
  public static Map<String, Object> sanitize(String kind, Map<String, Object> input) {
    var draft = Json.object(Json.write(input));
    var issues = new ArrayList<String>();
    if (draft.get("issues") instanceof List<?> list)
      list.forEach(issue -> issues.add(Objects.toString(issue)));
    if (kind.equals("INVOICE")) {
      if (!Inputs.rows(draft, "invoices").isEmpty())
        draft.put(
            "invoices",
            Inputs.rows(draft, "invoices").stream().map(r -> sanitize("INVOICE", r)).toList());
      dates(draft, List.of("issuedOn", "dueOn"), issues, "");
      money(draft, List.of("total", "net", "tax", "discount"), issues, "");
      if (Inputs.text(draft, "relatedInvoiceId") != null)
        try {
          UUID.fromString(draft.get("relatedInvoiceId").toString());
        } catch (Exception e) {
          draft.put("relatedInvoiceId", null);
          issues.add("Não foi reconhecida a referência à fatura original");
        }
      int n = 0;
      for (var line : Inputs.rows(draft, "lines")) {
        String prefix = "Artigo " + (++n) + ": ";
        money(line, List.of("net", "tax", "discount", "total"), issues, prefix);
        for (String key : List.of("quantity", "unitPrice"))
          if (Inputs.text(line, key) != null)
            try {
              new BigDecimal(line.get(key).toString()).setScale(6, RoundingMode.UNNECESSARY);
            } catch (Exception e) {
              line.put(key, null);
              issues.add(prefix + "valor ilegível em " + key);
            }
      }
    } else if (kind.equals("STATEMENT")) {
      for (String ignored :
          List.of(
              "periodStart", "periodEnd", "openingBalance", "closingBalance", "balanceDerivation"))
        draft.remove(ignored);
      int n = 0;
      for (var movement : Inputs.rows(draft, "movements")) {
        String prefix = "Movimento " + (++n) + ": ";
        dates(movement, List.of("bookedOn", "valueOn"), issues, prefix);
        money(movement, List.of("amount"), issues, prefix);
        if (Inputs.text(movement, "direction") != null
            && !Set.of("DEBIT", "CREDIT").contains(movement.get("direction"))) {
          movement.put("direction", null);
          issues.add(prefix + "débito/crédito por identificar");
        }
        if (Inputs.text(movement, "occurredAt") != null)
          try {
            LocalTime.parse(movement.get("occurredAt").toString());
          } catch (Exception e) {
            movement.put("occurredAt", null);
            issues.add(prefix + "hora ilegível");
          }
      }
    }
    draft.put("issues", issues);
    if (!issues.isEmpty()) draft.put("validate", false);
    return draft;
  }

  /**
   * Validates dates independently so one bad date cannot discard readable descriptions or amounts.
   */
  private static void dates(
      Map<String, Object> row, List<String> keys, List<String> issues, String prefix) {
    for (String key : keys)
      if (Inputs.text(row, key) != null)
        try {
          LocalDate.parse(row.get(key).toString());
        } catch (Exception e) {
          row.put(key, null);
          issues.add(prefix + "data ilegível em " + key);
        }
  }

  /**
   * Keeps malformed monetary values unknown rather than manufacturing zero or rejecting the whole
   * draft.
   */
  private static void money(
      Map<String, Object> row, List<String> keys, List<String> issues, String prefix) {
    for (String key : keys)
      if (Inputs.text(row, key) != null)
        try {
          Money.optional(row.get(key));
        } catch (Exception e) {
          row.put(key, null);
          issues.add(prefix + "montante ilegível em " + key);
        }
  }
}
