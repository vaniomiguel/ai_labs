package pt.conta.document;

import java.math.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.*;

/**
 * Preserves valid extracted fields when a different field cannot be converted to its domain type.
 */
public final class DraftSanitizer {
  private static final Set<String> VALUE_DATE_FIELDS = Set.of("valueOn", "value_on");
  private static final Set<String> VALUE_DATE_ISSUES = Set.of(
      "data ilegível em valueOn", "data ilegível em value_on",
      "Falta evidência para confirmar valueOn.", "Falta evidência para confirmar value_on.",
      "A IA sugeriu rever valueOn.", "A IA sugeriu rever value_on.");

  private DraftSanitizer() {}

  /** Copies incoming drafts and drops retired fields at the extraction and manual-write boundaries. */
  static Map<String, Object> copyWithoutRetiredFields(String kind, Map<String, Object> input) {
    var draft = Json.object(Json.write(input));
    if (kind.equals("INVOICE")) {
      removeInvoiceDueDates(draft);
      Inputs.rows(draft, "invoices").forEach(DraftSanitizer::removeInvoiceDueDates);
    } else if (kind.equals("STATEMENT")) {
      removeMovementValueDates(draft);
      Inputs.rows(draft, "movements").forEach(DraftSanitizer::removeMovementValueDates);
    }
    return draft;
  }

  private static void removeInvoiceDueDates(Map<String, Object> row) {
    row.remove("dueOn");
    row.remove("due_on");
  }

  private static void removeMovementValueDates(Map<String, Object> row) {
    row.keySet().removeAll(VALUE_DATE_FIELDS);
    removeStatementValueDateFindings(row);
  }

  public static void removeStatementValueDateFindings(Map<String, Object> draft) {
    if (draft.get("issues") instanceof List<?> issues) {
      draft.put("issues", issues.stream()
          .filter(issue -> !(issue instanceof String message && VALUE_DATE_ISSUES.contains(message)))
          .toList());
    }
    if (draft.containsKey("evidence")) {
      draft.put("evidence", withoutStatementValueDateEvidence(draft.get("evidence")));
    }
  }

  public static Object withoutStatementValueDateEvidence(Object evidence) {
    if (!(evidence instanceof List<?> entries)) return evidence;
    return entries.stream().filter(entry -> !(entry instanceof Map<?, ?> item
        && (valueDatePath(item.get("field")) || valueDatePath(item.get("path"))))).toList();
  }

  private static boolean valueDatePath(Object value) {
    return value instanceof String path && VALUE_DATE_FIELDS.contains(path.substring(path.lastIndexOf('.') + 1));
  }

  /**
   * Copies an automatic extraction, blanks only malformed typed fields and records review reasons.
   */
  public static Map<String, Object> sanitize(String kind, Map<String, Object> input) {
    var draft = copyWithoutRetiredFields(kind, input);
    var issues = issues(draft);
    if (kind.equals("INVOICE")) {
      if (!Inputs.rows(draft, "invoices").isEmpty())
        draft.put(
            "invoices",
            Inputs.rows(draft, "invoices").stream().map(r -> sanitize("INVOICE", r)).toList());
      dates(draft, List.of("issuedOn"), issues, "");
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
      for (var movement : Inputs.rows(draft, "movements")) {
        var rowIssues = issues(movement);
        dates(movement, List.of("bookedOn"), rowIssues, "");
        money(movement, List.of("amount"), rowIssues, "");
        if (Inputs.text(movement, "direction") != null
            && !Set.of("DEBIT", "CREDIT").contains(movement.get("direction"))) {
          movement.put("direction", null);
          rowIssues.add("Débito/crédito por identificar");
        }
        if (Inputs.text(movement, "occurredAt") != null)
          try {
            LocalTime.parse(movement.get("occurredAt").toString());
          } catch (Exception e) {
            movement.put("occurredAt", null);
            rowIssues.add("Hora ilegível");
          }
        movement.put("issues", rowIssues);
        if (!rowIssues.isEmpty()) movement.put("validate", false);
      }
    }
    draft.put("issues", issues);
    if (!issues.isEmpty()) draft.put("validate", false);
    return draft;
  }

  private static List<String> issues(Map<String, Object> row) {
    var issues = new ArrayList<String>();
    if (row.get("issues") instanceof List<?> entries)
      entries.forEach(issue -> issues.add(Objects.toString(issue)));
    return issues;
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
