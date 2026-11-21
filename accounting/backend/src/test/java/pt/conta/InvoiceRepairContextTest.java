package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static pt.conta.shared.Inputs.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import pt.conta.ai.*;
import pt.conta.audit.AuditService;
import pt.conta.document.FieldProtection;
import pt.conta.shared.*;

/** Verifies model-visible field state and response templates against the real ownership rules. */
class InvoiceRepairContextTest {
  private final InvoiceRepairContext builder =
      new InvoiceRepairContext(new FieldProtection(mock(AuditService.class)));
  private final Map<String, Object> schema = new PromptCatalog(mock(AccountingProperties.class))
      .schema(PromptCatalog.INVOICE_EXTRACTION_SYSTEM_INSTRUCTIONS);

  private InvoiceRepairContext.FilledField filled(InvoiceRepairContext.Context context, String path) {
    return context.filledFields().stream().filter(field -> field.path().equals(path)).findFirst().orElseThrow();
  }

  private InvoiceRepairContext.MissingField missing(InvoiceRepairContext.Context context, String path) {
    return context.fieldsToExtract().stream().filter(field -> field.path().equals(path)).findFirst().orElseThrow();
  }

  private Map<String, Object> completeInvoice() {
    return map("issuedOn", "2026-01-01", "number", "FT 001/01",
        "supplier", "Saved supplier", "supplierTaxId", "PT123456789", "description", "Office supplies",
        "total", "123.00", "net", "100.00", "tax", "23.00", "discount", "0.00", "currency", "EUR",
        "type", "INVOICE", "categories", List.of(), "lines", List.of(map("description", "Paper",
            "quantity", "1.250000", "unitPrice", "80.000000", "net", "100.00", "tax", "23.00",
            "discount", "0.00", "total", "123.00")));
  }

  @Test
  void retiredDueDateIsNeverAnExtractionTargetOrResponseField() {
    var draft = map("issuedOn", null, "dueOn", "2026-02-01");
    var context = builder.build(draft,
        map("root", map("dueOn", map("origin", "HUMAN_EDIT", "protected", true))), schema);
    assertThat(context.fieldsToExtract()).extracting(InvoiceRepairContext.MissingField::path)
        .contains("issuedOn").doesNotContain("dueOn");
    assertThat(context.filledFields()).extracting(InvoiceRepairContext.FilledField::path).doesNotContain("dueOn");
    assertThat(Json.write(context.responseTemplate())).doesNotContain("dueOn");
    assertThat(Json.write(schema)).doesNotContain("dueOn");
  }

  @Test
  void identifiesBlanksPrioritisesRequiredGapsAndKeepsZeroAsFilled() {
    var context = builder.build(map("supplier", " \t", "issuedOn", null, "total", 0,
        "currency", "EUR", "type", "INVOICE", "tax", "", "lines", List.of()), Map.of(), schema);
    assertThat(context.fieldsToExtract()).extracting(InvoiceRepairContext.MissingField::path)
        .startsWith("issuedOn", "supplier").doesNotContain("categories", "relatedInvoiceId");
    assertThat(missing(context, "tax").requiredForValidation()).isFalse();
    assertThat(missing(context, "lines").requiredForValidation()).isFalse();
    assertThat(filled(context, "total").value()).isEqualTo(0);
    assertThat(filled(context, "total").requiredForValidation()).isTrue();
    assertThat(filled(context, "total").origin()).isEqualTo("UNKNOWN");
    assertThat(filled(context, "total").correctionAllowed()).isFalse();
    assertThat(Json.read(Json.write(context.responseTemplate())).path("draft").path("total").asText())
        .isEqualTo("0");
    assertThatCode(() -> StructuredOutput.parse(Json.write(context.responseTemplate()), schema))
        .doesNotThrowAnyException();
  }

  @Test
  void separatesHumanAutomaticUnknownAndExplicitProtection() {
    var draft = map("supplier", "Human supplier", "total", "100.00", "number", "Known number",
        "tax", "23.00", "description", "Human description");
    var provenance = map("root", map(
        "supplier", map("origin", "HUMAN_EDIT", "protected", true),
        "total", map("origin", "AUTOMATIC", "protected", false),
        "tax", map("origin", "AUTOMATIC", "protected", true),
        "description", map("origin", "HUMAN_CONFIRMED", "protected", false)));
    var context = builder.build(draft, provenance, schema);
    assertThat(filled(context, "supplier").authoritative()).isTrue();
    assertThat(filled(context, "supplier").correctionAllowed()).isFalse();
    assertThat(filled(context, "total").authoritative()).isFalse();
    assertThat(filled(context, "total").correctionAllowed()).isTrue();
    assertThat(filled(context, "number").origin()).isEqualTo("UNKNOWN");
    assertThat(filled(context, "number").authoritative()).isFalse();
    assertThat(filled(context, "number").correctionAllowed()).isFalse();
    assertThat(filled(context, "tax").authoritative()).isTrue();
    assertThat(filled(context, "description").authoritative()).isTrue();
  }

  @Test
  void carriesLegacyProtectionIntoFilledFieldsAndMissingArticleValues() {
    var draft = map("supplier", "Legacy supplier", "lines", List.of(map("description", "Known item")));
    var context = builder.build(draft, map("_legacy", map("includeEmpty", false)), schema);
    assertThat(filled(context, "supplier").authoritative()).isTrue();
    assertThat(filled(context, "supplier").origin()).isEqualTo("UNKNOWN");
    assertThat(filled(context, "lines[0].description").authoritative()).isTrue();
    assertThat(missing(context, "lines[0].total").authoritative()).isTrue();
    assertThat(missing(context, "lines[0].total").requiredForValidation()).isTrue();
    assertThat(missing(context, "issuedOn").authoritative()).isFalse();
  }

  @Test
  void keepsDeliberatelyProtectedBlanksAndDistinguishesSchemaFromBusinessRequirements() {
    var provenance = map("root", map("supplier", map("origin", "HUMAN_EDIT", "protected", true),
        "lines", map("origin", "HUMAN_EDIT", "protected", true)));
    var context = builder.build(map("supplier", null, "lines", List.of(map("quantity", "1"))), provenance, schema);
    assertThat(missing(context, "supplier").authoritative()).isTrue();
    assertThat(missing(context, "lines[0].description").requiredForValidation()).isFalse();
    assertThat(missing(context, "lines[0].description").authoritative()).isTrue();
    assertThat(missing(context, "lines[0].total").requiredForValidation()).isTrue();
    assertThat(filled(context, "lines[0].quantity").correctionAllowed()).isFalse();
  }

  @Test
  void indexesReceiptsAndItemsAndFiltersPersistenceFieldsWithoutMutatingInputs() {
    var first = map("id", "receipt-a", "supplier", "Supplier A", "categories", List.of("Old category"),
        "version", 4, "validate", true, "lines", List.of(map("id", "line-a", "invoiceId", "receipt-a",
            "position", 0, "description", "Item A", "total", "10.00")));
    var second = map("_itemId", "receipt-b", "supplier", "Supplier B", "issues", List.of("Old issue"),
        "lines", List.of(map("description", "Item B", "total", null)));
    var draft = map("documentId", "internal-document", "invoices", List.of(first, second));
    var provenance = map("receipt-a", map("supplier", map("origin", "HUMAN_EDIT", "protected", true)),
        "receipt-b", map("supplier", map("origin", "AUTOMATIC", "protected", false)));
    String before = Json.write(draft);
    String ownershipBefore = Json.write(provenance);
    var context = builder.build(draft, provenance, schema);
    assertThat(filled(context, "invoices[0].supplier").value()).isEqualTo("Supplier A");
    assertThat(filled(context, "invoices[0].supplier").authoritative()).isTrue();
    assertThat(filled(context, "invoices[1].supplier").value()).isEqualTo("Supplier B");
    assertThat(filled(context, "invoices[1].supplier").correctionAllowed()).isTrue();
    assertThat(filled(context, "invoices[0].lines[0].total").value()).isEqualTo("10.00");
    assertThat(missing(context, "invoices[1].lines[0].total").requiredForValidation()).isTrue();
    var template = Json.read(Json.write(context.responseTemplate()));
    assertThat(template.path("draft").path("invoices").get(0).path("supplier").asText()).isEqualTo("Supplier A");
    assertThat(template.path("draft").path("invoices").get(1).path("supplier").asText()).isEqualTo("Supplier B");
    assertThat(template.path("draft").path("invoices").get(0).path("categories").isEmpty()).isTrue();
    assertThat(Json.write(template)).doesNotContain("receipt-a", "receipt-b", "line-a", "internal-document",
        "invoiceId", "position", "validate", "Old category", "Old issue");
    assertThatCode(() -> StructuredOutput.parse(Json.write(template), schema)).doesNotThrowAnyException();
    assertThat(Json.write(draft)).isEqualTo(before);
    assertThat(Json.write(provenance)).isEqualTo(ownershipBefore);
  }

  @Test
  void completeInvoicesHaveNoExtractionTargetsAndRetainPreciseValues() {
    var context = builder.build(completeInvoice(), Map.of(), schema);
    assertThat(context.fieldsToExtract()).isEmpty();
    var proposed = Json.read(Json.write(context.responseTemplate())).path("draft");
    assertThat(proposed.path("lines").get(0).path("unitPrice").asText()).isEqualTo("80.000000");
    assertThat(proposed.path("discount").asText()).isEqualTo("0.00");
    assertThat(proposed.path("supplier").asText()).isEqualTo("Saved supplier");
  }

  @Test
  void creditNoteLinkIsRequiredButNeverAnOcrExtractionOrCorrectionTarget() {
    var draft = completeInvoice();
    draft.put("type", "CREDIT_NOTE");
    var missingLink = builder.build(draft, Map.of(), schema);
    assertThat(missingLink.fieldsToExtract()).extracting(InvoiceRepairContext.MissingField::path)
        .doesNotContain("relatedInvoiceId");
    String original = UUID.randomUUID().toString();
    draft.put("relatedInvoiceId", original);
    var context = builder.build(draft,
        map("root", map("relatedInvoiceId", map("origin", "AUTOMATIC", "protected", false))), schema);
    assertThat(filled(context, "relatedInvoiceId").requiredForValidation()).isTrue();
    assertThat(filled(context, "relatedInvoiceId").correctionAllowed()).isFalse();
    assertThat(filled(context, "relatedInvoiceId").value()).isEqualTo(original);
  }
}
