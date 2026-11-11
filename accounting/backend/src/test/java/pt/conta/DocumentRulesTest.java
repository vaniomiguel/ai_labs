package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.map;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import pt.conta.ai.*;
import pt.conta.document.PortugueseDocumentParser;
import pt.conta.history.*;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.*;

/** Tests parser uncertainty, temporal rules, Excel provenance and structured-output validation. */
class DocumentRulesTest {
  @Test
  void malformedFieldDoesNotDiscardReadableExtractedValues() {
    var draft =
        pt.conta.document.DraftSanitizer.sanitize(
            "INVOICE",
            map(
                "issuedOn",
                "data ilegível",
                "supplier",
                "Fornecedor conhecido",
                "total",
                "12.00",
                "lines",
                List.of(),
                "validate",
                true));
    assertThat(draft.get("issuedOn")).isNull();
    assertThat(draft.get("supplier")).isEqualTo("Fornecedor conhecido");
    assertThat(draft.get("total")).isEqualTo("12.00");
    assertThat(draft.get("validate")).isEqualTo(false);
  }

  @Test
  void bankRowsKeepAmountsWhenDateOrDirectionCannotBeRead() {
    var parsed = new PortugueseDocumentParser().statement("32/01/2026 Compra de teste 12,00\n");
    var rows = Inputs.rows(parsed, "movements");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("bookedOn")).isNull();
    assertThat(rows.getFirst().get("direction")).isNull();
    assertThat(rows.getFirst().get("amount")).isEqualTo("12.00");
  }

  @Test
  void calendarMonthWindowIsSymmetricAndNotNinetyDays() {
    assertThat(MatchingRules.eligible(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 3, 31), 3))
        .isTrue();
    assertThat(MatchingRules.eligible(LocalDate.of(2026, 4, 1), LocalDate.of(2025, 12, 31), 3))
        .isFalse();
    assertThat(MatchingRules.eligible(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 1, 1), 0))
        .isTrue();
  }

  @Test
  void missingAndConflictingTotalsNeverBecomeZero() {
    var parser = new PortugueseDocumentParser();
    var missing = parser.invoice("Fornecedor: Exemplo\nData: 11/11/2026\n", "fatura.pdf");
    assertThat(missing.get("total")).isNull();
    var conflict = parser.invoice("Total: 12,00\nTotal: 13,00\n", "fatura.pdf");
    assertThat(conflict.get("total")).isNull();
    assertThat((List<?>) conflict.get("issues")).isNotEmpty();
    assertThat(PortugueseDocumentParser.amount("1.234,56")).isEqualTo("1234.56");
  }

  @Test
  void labelsExtractExactlyAndFilenameOnlySuppliesCategories() {
    var draft =
        new PortugueseDocumentParser()
            .invoice(
                "Fornecedor: Exemplo\nData: 11/11/2026\nFatura: FT2026/123\nTotal: 123,45\n",
                "20261111_Combustível_Transportes.pdf");
    assertThat(draft.get("total")).isEqualTo("123.45");
    assertThat(draft.get("issuedOn")).isEqualTo("2026-11-11");
    assertThat(draft.get("categories")).isEqualTo(List.of("Combustível", "Transportes"));
  }

  @Test
  void invalidArchivePathsAreRejected() {
    assertThatThrownBy(() -> ZipImporter.validatePath("2016/../secret.pdf"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> ZipImporter.validatePath("/tmp/source.pdf"))
        .isInstanceOf(DomainException.class);
    assertThatCode(() -> ZipImporter.validatePath("2016/01/despesas/fatura.pdf"))
        .doesNotThrowAnyException();
  }

  @Test
  void structuredOutputRejectsWrongTypesAndUnknownFields() {
    var schema =
        map(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            map("total", map("type", List.of("string", "null"))),
            "required",
            List.of("total"));
    assertThatThrownBy(() -> StructuredOutput.parse("{\"total\":12}", schema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> StructuredOutput.parse("{\"total\":\"12.00\",\"invented\":true}", schema))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(StructuredOutput.parse("{\"total\":null}", schema)).containsKey("total");
  }

  @Test
  void selfConfidenceWithoutSourceEvidenceCannotApproveAnInvoice() {
    var draft = map("issuedOn", "2026-11-11", "supplier", "Exemplo", "total", "12.00");
    assertThat(AiEvidence.supportsInvoice(draft, List.of(), "Fornecedor: Exemplo")).isFalse();
  }

  @Test
  void excelTotalsAreNotImportedAsExpenseRowsAndFormulasArePreserved() throws Exception {
    var path = Files.createTempFile("conta-history-2026-", ".xlsx");
    try (var workbook = new XSSFWorkbook()) {
      var sheet = workbook.createSheet("Maio");
      var header = sheet.createRow(0);
      String[] labels = {"Data", "Descrição", "Recebido", "Pagamento", "Saldo"};
      for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
      for (int i = 1; i <= 3; i++) {
        var row = sheet.createRow(i);
        row.createCell(0).setCellValue("0" + i + "/05/2026");
        row.createCell(1).setCellValue("Item " + i);
        row.createCell(2).setCellValue(new double[] {0, 3, 4, 2}[i]);
        if (i == 2) row.createCell(3).setCellValue(3);
        row.createCell(4)
            .setCellFormula(i == 1 ? "C2-D2" : "E" + i + "+C" + (i + 1) + "-D" + (i + 1));
      }
      var total = sheet.createRow(4);
      total.createCell(1).setCellValue("Total recebido");
      total.createCell(2).setCellFormula("SUM(C2:C4)");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      try (var out = Files.newOutputStream(path)) {
        workbook.write(out);
      }
    }
    var result = new ExcelReader().read(path, "HISTORY", "2026.xlsx", Map.of());
    var rows = Inputs.rows(result, "rows");
    assertThat(rows).hasSize(3);
    assertThat(rows.stream().map(r -> r.get("balance"))).containsExactly("3.00", "4.00", "6.00");
    assertThat(Json.write(rows.getFirst().get("cells"))).contains("formula");
    Files.delete(path);
  }
}
