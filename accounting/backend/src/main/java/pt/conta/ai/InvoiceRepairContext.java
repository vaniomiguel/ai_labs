package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;
import pt.conta.document.FieldProtection;
import pt.conta.shared.Json;

/** Describes saved invoice gaps and ownership without changing the draft or its provenance. */
@Component
public class InvoiceRepairContext {
  private static final Set<String> REQUIRED_INVOICE_FIELDS = Set.of("issuedOn", "supplier", "total", "currency");
  private final FieldProtection protection;

  public InvoiceRepairContext(FieldProtection protection) {
    this.protection = protection;
  }

  public record MissingField(String path, boolean requiredForValidation, boolean authoritative) {}

  public record FilledField(
      String path, Object value, boolean requiredForValidation, String origin,
      boolean authoritative, boolean correctionAllowed) {}

  public record Context(
      List<MissingField> fieldsToExtract, List<FilledField> filledFields,
      Map<String, Object> responseTemplate) {}

  public Context build(
      Map<String, Object> draft, Map<String, Object> provenance, Map<String, Object> schema) {
    var draftSchema = Json.read(Json.write(schema)).path("properties").path("draft");
    var missing = new ArrayList<MissingField>();
    var filled = new ArrayList<FilledField>();
    boolean root = protection.root("INVOICE", draft);
    var entries = protection.entries("INVOICE", draft);
    var invoiceSchema = root ? draftSchema : draftSchema.path("properties").path("invoices").path("items");
    for (int n = 0; n < entries.size(); n++) {
      var row = entries.get(n);
      String prefix = root ? "" : "invoices[" + n + "].";
      var ownership = protection.rowMetadata(provenance, protection.rowKey(row, root));
      collectInvoice(row, invoiceSchema, ownership, prefix, missing, filled);
    }
    missing.sort(Comparator.comparing(MissingField::requiredForValidation).reversed());
    var proposed = projectObject(root ? draft : Map.of(), draftSchema);
    if (!root) {
      var invoices = new ArrayList<Map<String, Object>>();
      for (var row : entries) invoices.add(projectObject(row, invoiceSchema));
      proposed.put("invoices", invoices);
    }
    var template = map("draft", proposed, "evidence", List.of(), "issues", List.of());
    StructuredOutput.parse(Json.write(template), schema);
    return new Context(List.copyOf(missing), List.copyOf(filled), template);
  }

  private void collectInvoice(
      Map<String, Object> row, JsonNode schema, Map<String, Object> ownership, String prefix,
      List<MissingField> missing, List<FilledField> filled) {
    for (var field : schema.path("properties").properties()) {
      String name = field.getKey();
      if (name.equals("invoices") || name.equals("categories")) continue;
      if (name.equals("lines")) {
        collectLines(row, field.getValue(), ownership, prefix, missing, filled);
      } else {
        boolean required = REQUIRED_INVOICE_FIELDS.contains(name)
            || name.equals("relatedInvoiceId") && "CREDIT_NOTE".equals(row.get("type"));
        collectField(prefix + name, name, row.get(name), required, ownership, missing, filled);
      }
    }
  }

  private void collectLines(
      Map<String, Object> row, JsonNode schema, Map<String, Object> ownership, String prefix,
      List<MissingField> missing, List<FilledField> filled) {
    var lines = rows(row, "lines");
    if (lines.isEmpty()) {
      collectField(prefix + "lines", "lines", row.get("lines"), false, ownership, missing, filled);
      return;
    }
    var lineOwnership = protection.copy(ownership);
    if (protection.protectedField(ownership, "lines", row.get("lines"))) {
      var decision = protection.copy(ownership.get("lines"));
      decision.put("protected", true);
      lineOwnership.put("lines", decision);
    }
    var fields = schema.path("items").path("properties");
    for (int n = 0; n < lines.size(); n++) {
      var names = fields.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        // Provenance protects the saved article collection as a whole, including its gaps.
        collectField(prefix + "lines[" + n + "]." + name, "lines", lines.get(n).get(name),
            name.equals("total"), lineOwnership, missing, filled);
      }
    }
  }

  private void collectField(
      String path, String ownershipField, Object value, boolean required, Map<String, Object> ownership,
      List<MissingField> missing, List<FilledField> filled) {
    var decision = protection.copy(ownership.get(ownershipField));
    String origin = Objects.toString(decision.get("origin"), "UNKNOWN");
    boolean authoritative = origin.startsWith("HUMAN_") || origin.equals("MANUAL")
        || protection.protectedField(ownership, ownershipField, value);
    if (protection.empty(value)) {
      // An application UUID cannot be discovered in invoice OCR; the prompt flags an unresolved credit note.
      if (!ownershipField.equals("relatedInvoiceId")) missing.add(new MissingField(path, required, authoritative));
    } else {
      boolean correctionAllowed = origin.equals("AUTOMATIC") && !authoritative
          && !ownershipField.equals("relatedInvoiceId");
      filled.add(new FilledField(path, value, required, origin, authoritative, correctionAllowed));
    }
  }

  private Map<String, Object> projectObject(Map<String, Object> saved, JsonNode schema) {
    var projected = new LinkedHashMap<String, Object>();
    for (var field : schema.path("properties").properties()) {
      String name = field.getKey();
      if (name.equals("invoices")) continue;
      projected.put(name, projectValue(name, saved.get(name), field.getValue()));
    }
    return projected;
  }

  private Object projectValue(String field, Object value, JsonNode schema) {
    if (field.equals("categories")) return List.of();
    if (schema.path("type").asText().equals("array")) {
      var items = new ArrayList<Map<String, Object>>();
      if (value instanceof List<?> list)
        for (var item : list) items.add(projectObject(protection.copy(item), schema.path("items")));
      return items;
    }
    if (!protection.empty(value)) return value.toString();
    return switch (field) {
      case "currency" -> "EUR";
      case "type" -> "INVOICE";
      default -> null;
    };
  }
}
