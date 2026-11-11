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

  @Test
  void bankBalancesAreIgnoredRatherThanComparedWithMovementAmounts() {
    var result =
        new PortugueseDocumentParser()
            .statement(
                "Saldo contabilístico 9.999,99 EUR\n"
                    + "Saldo contabilístico\n"
                    + "após movimento\n"
                    + "Tipos de movimento Todos\n"
                    + "Intervalo de 01-02-2016 a 29-02-2016\n"
                    + "29-02-2016 29-02-2016 Mensalidade -10,00 IGNORADO\n"
                    + "29-02-2016 29-02-2016 Transferencia 500,00 -999.999,99\n"
                    + "18-02-2016 18-02-2016 TSU -30,00 58,00\n"
                    + "17-02-2016 17-02-2016 Imposto -12,00 88,00\n");
    assertThat(result).doesNotContainKeys("openingBalance", "closingBalance", "balanceDerivation");
    assertThat(Inputs.rows(result, "movements").getFirst().get("amount")).isEqualTo("10.00");
    assertThat(result.get("validate")).isEqualTo(true);
  }

  @Test
  void splitOpeningBalanceAndCrossYearDatePreserveOriginalSheet() throws Exception {
    var path = Files.createTempFile("conta-split-opening-", ".xlsx");
    try (var workbook = new XSSFWorkbook()) {
      var sheet = workbook.createSheet("Janeiro");
      sheet.createRow(7).createCell(6).setCellValue("Início");
      var opening = sheet.createRow(8);
      opening.createCell(6).setCellValue("Saldo");
      opening.createCell(7).setCellValue(90.25);
      var header = sheet.createRow(12);
      String[] names = {"Data", "Descrição do item", "Recebido", "Pagamento", "Saldo"};
      int[] columns = {2, 3, 6, 7, 8};
      for (int n = 0; n < names.length; n++) header.createCell(columns[n]).setCellValue(names[n]);
      var row = sheet.createRow(13);
      row.createCell(2).setCellValue("02/12/2015");
      row.createCell(3).setCellValue("Alojamento");
      row.createCell(6).setCellValue(10);
      row.createCell(8).setCellValue(100.25);
      try (var out = Files.newOutputStream(path)) {
        workbook.write(out);
      }
    }
    var draft = new ExcelReader().read(path, "HISTORY", "2016.xlsx", Map.of());
    assertThat((List<?>) draft.get("issues")).isEmpty();
    assertThat(Inputs.rows(draft, "sheets").getFirst().get("controls").toString())
        .contains("90.25");
    assertThat(Inputs.rows(draft, "rows").getFirst())
        .containsEntry("sheet", "Janeiro")
        .containsEntry("date", "2015-12-02");
    Files.delete(path);
  }

  @Test
  void statementsNeedOnlyTheFourMovementFieldsAndTheirEvidence() {
    String source = "02/01/2026 Compra de teste -7,00";
    var row =
        map(
            "bookedOn",
            "2026-01-02",
            "description",
            "Compra de teste",
            "amount",
            "7.00",
            "direction",
            "DEBIT");
    var proof = new ArrayList<Map<String, Object>>();
    for (String field : List.of("bookedOn", "description", "amount", "direction"))
      proof.add(map("field", "movements[0]." + field, "quote", source));
    assertThat(AiEvidence.supportsStatement(map("movements", List.of(row)), proof, source))
        .isTrue();
    proof.removeLast();
    assertThat(AiEvidence.supportsStatement(map("movements", List.of(row)), proof, source))
        .isFalse();
  }

  @Test
  void completeMovementsWithoutControlBalancesOrPeriodAreValid() {
    var result =
        new PortugueseDocumentParser()
            .statement("02/01/2026 Compra -7,00\n03/01/2026 Devolucao 3,00 C\n");
    assertThat(result.get("validate")).isEqualTo(true);
    assertThat(Inputs.rows(result, "movements")).hasSize(2);
    assertThat(result).doesNotContainKeys("openingBalance", "closingBalance");
    var cgd =
        new PortugueseDocumentParser()
            .statement(
                "Saldo contabilístico após movimento\n"
                    + "02-01-2026 02-01-2026 Compra -7,00  \n"
                    + "CONTA\n");
    assertThat(cgd.get("validate")).isEqualTo(true);
    assertThat(Inputs.rows(cgd, "movements").getFirst())
        .containsEntry("amount", "7.00")
        .containsEntry("description", "Compra CONTA");
  }
}
