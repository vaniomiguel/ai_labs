package pt.conta.history;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import pt.conta.document.PortugueseDocumentParser;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.*;

/** Reads original Excel cells, cached formulas and annual/monthly historical structures. */
@Component
public class ExcelReader {
  private static final Map<String, List<String>> ALIASES =
      Map.of(
          "date",
          List.of("DATA", "DATA MOVIMENTO", "DATA OPERACAO"),
          "description",
          List.of("DESCRICAO", "DESCRICAO DO ITEM", "ITEM", "DESCRITIVO"),
          "received",
          List.of("RECEBIDO", "RECEBIDOS"),
          "payment",
          List.of("PAGAMENTO", "PAGAMENTOS"),
          "balance",
          List.of("SALDO"),
          "debit",
          List.of("DEBITO", "DEBITOS"),
          "credit",
          List.of("CREDITO", "CREDITOS"),
          "amount",
          List.of("VALOR", "MONTANTE"));
  private static final List<String> MONTHS =
      List.of(
          "JANEIRO",
          "FEVEREIRO",
          "MARCO",
          "ABRIL",
          "MAIO",
          "JUNHO",
          "JULHO",
          "AGOSTO",
          "SETEMBRO",
          "OUTUBRO",
          "NOVEMBRO",
          "DEZEMBRO");

  /** Extracts one workbook; a custom zero-based column mapping can replace header recognition. */
  public Map<String, Object> read(
      Path path, String kind, String filename, Map<String, Object> mapping) throws Exception {
    var sheets = new ArrayList<Map<String, Object>>();
    var items = new ArrayList<Map<String, Object>>();
    var issues = new ArrayList<String>();
    var movements = new ArrayList<Map<String, Object>>();
    var format = new DataFormatter(Locale.forLanguageTag("pt-PT"));
    format.setUseCachedValuesForFormulaCells(true);
    Integer detectedYear = year(filename);
    BigDecimal opening = null, closing = null;
    try (var workbook = WorkbookFactory.create(path.toFile(), null, true)) {
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      for (var sheet : workbook) {
        BigDecimal sheetOpening = null,
            expectedReceived = null,
            expectedPayment = null,
            expectedClosing = null;
        if (sheet.getLastRowNum() > 100000)
          throw DomainException.invalid("A folha excede 100 000 linhas");
        var grid = new ArrayList<List<String>>();
        Map<String, Integer> columns = new HashMap<>();
        int header = -1;
        for (var row : sheet) {
          var cells = new ArrayList<String>();
          for (int c = 0; c < Math.min(100, Math.max(0, row.getLastCellNum())); c++)
            cells.add(format.formatCellValue(row.getCell(c)));
          grid.add(cells);
          if (detectedYear == null) detectedYear = year(String.join(" ", cells));
          for (int c = 0; c < cells.size(); c++) {
            String normalized = MatchingRules.normalize(cells.get(c));
            boolean splitOpening =
                normalized.equals("SALDO")
                    && row.getRowNum() > 0
                    && MatchingRules.normalize(cell(sheet.getRow(row.getRowNum() - 1), c, format))
                        .equals("INICIO");
            if (normalized.equals("SALDO INICIAL")
                || normalized.equals("INICIO DE SALDO")
                || splitOpening) {
              sheetOpening = nextAmount(row, c, format);
              if (opening == null) opening = sheetOpening;
            }
            if (normalized.matches("TOTAL (?:DE )?RECEBIDOS?"))
              expectedReceived = nextAmount(row, c, format);
            if (normalized.matches("TOTAL (?:DE )?PAGAMENTOS?"))
              expectedPayment = nextAmount(row, c, format);
            if (normalized.equals("SALDO FINAL")
                || normalized.equals("SALDO ACTUAL A TRANSITAR")
                || normalized.equals("SALDO ATUAL A TRANSITAR")) {
              expectedClosing = nextAmount(row, c, format);
              closing = expectedClosing;
            }
          }
          if (header < 0) {
            var candidate = new HashMap<String, Integer>();
            for (int c = 0; c < cells.size(); c++)
              for (var alias : ALIASES.entrySet())
                if (alias.getValue().contains(MatchingRules.normalize(cells.get(c))))
                  candidate.put(alias.getKey(), c);
            if (candidate.containsKey("date")
                && candidate.containsKey("description")
                && candidate.size() >= 3) {
              columns = candidate;
              header = row.getRowNum();
            }
          }
        }
        if (!mapping.isEmpty()) {
          for (var e : mapping.entrySet())
            if (ALIASES.containsKey(e.getKey()) && e.getValue() instanceof Number n)
              columns.put(e.getKey(), n.intValue());
          if (mapping.get("headerRow") instanceof Number n) header = n.intValue();
        }
        var sheetResult =
            Inputs.map(
                "name",
                sheet.getSheetName(),
                "cells",
                grid,
                "mapping",
                columns,
                "headerRow",
                header,
                "controls",
                Inputs.map(
                    "opening",
                    Money.text(sheetOpening),
                    "received",
                    Money.text(expectedReceived),
                    "payment",
                    Money.text(expectedPayment),
                    "closing",
                    Money.text(expectedClosing)));
        if (!kind.equals("HISTORY")) sheetResult.remove("controls");
        sheets.add(sheetResult);
        if (header < 0) {
          issues.add("Não foi reconhecido o cabeçalho da folha " + sheet.getSheetName());
          continue;
        }
        int month = month(sheet.getSheetName());
        BigDecimal running = sheetOpening == null ? Money.ZERO : sheetOpening,
            receivedSum = Money.ZERO,
            paymentSum = Money.ZERO;
        for (var row : sheet) {
          if (row.getRowNum() <= header) continue;
          String description = cell(row, columns.get("description"), format);
          String normalized = MatchingRules.normalize(description);
          String rawDate = cell(row, columns.get("date"), format);
          if (rawDate.isBlank()
              && normalized.matches(
                  "^(?:TOTAL(?: .*?)?|INICIO DE SALDO|SALDO A TRANSITAR|SALDO FINAL)$")) continue;
          if (rawDate.isBlank() && description.isBlank()) continue;
          LocalDate date = null;
          var rowIssues = new ArrayList<String>();
          try {
            Cell dateCell = columns.containsKey("date") ? row.getCell(columns.get("date")) : null;
            if (dateCell != null
                && dateCell.getCellType() == CellType.NUMERIC
                && DateUtil.isCellDateFormatted(dateCell))
              date = dateCell.getLocalDateTimeCellValue().toLocalDate();
            else if (!rawDate.isBlank())
              date = PortugueseDocumentParser.date(rawDate, detectedYear);
          } catch (Exception e) {
            rowIssues.add("Data inválida");
          }
          if (date != null && detectedYear == null) detectedYear = date.getYear();
          var original = new LinkedHashMap<String, Object>();
          for (Cell cell : row) {
            var entry = Inputs.map("value", format.formatCellValue(cell));
            if (cell.getCellType() == CellType.FORMULA) {
              entry.put("formula", cell.getCellFormula());
              try {
                var calculated = evaluator.evaluate(cell);
                entry.put("evaluated", calculated == null ? null : calculated.formatAsString());
                if (calculated != null && calculated.getCellType() == CellType.ERROR)
                  rowIssues.add("Fórmula inválida em " + cell.getAddress());
              } catch (Exception e) {
                rowIssues.add("Fórmula não suportada em " + cell.getAddress());
              }
            }
            original.put(cell.getAddress().formatAsString(), entry);
          }
          try {
            if (kind.equals("HISTORY")) {
              BigDecimal received = numeric(row, columns.get("received"), format),
                  payment = numeric(row, columns.get("payment"), format),
                  balance = numeric(row, columns.get("balance"), format);
              if (received == null && payment == null) continue;
              if (date == null) rowIssues.add("Falta a data");
              running = running.add(Money.orZero(received)).subtract(Money.orZero(payment));
              receivedSum = receivedSum.add(Money.orZero(received));
              paymentSum = paymentSum.add(Money.orZero(payment));
              if (balance != null && running.compareTo(balance) != 0)
                rowIssues.add("Saldo acumulado diferente do calculado");
              items.add(
                  Inputs.map(
                      "sheet",
                      sheet.getSheetName(),
                      "rowNumber",
                      row.getRowNum() + 1,
                      "year",
                      date == null ? detectedYear : date.getYear(),
                      "month",
                      date == null ? month : date.getMonthValue(),
                      "date",
                      date == null ? null : date.toString(),
                      "description",
                      description,
                      "received",
                      Money.text(received),
                      "payment",
                      Money.text(payment),
                      "balance",
                      Money.text(balance),
                      "cells",
                      original,
                      "issues",
                      rowIssues));
            } else {
              BigDecimal debit = numeric(row, columns.get("debit"), format),
                  credit = numeric(row, columns.get("credit"), format),
                  amount = numeric(row, columns.get("amount"), format);
              String direction = null;
              if (debit != null && debit.signum() != 0 && credit != null && credit.signum() != 0)
                rowIssues.add("Débito e crédito preenchidos na mesma linha");
              else if (debit != null && debit.signum() != 0) {
                amount = debit.abs();
                direction = "DEBIT";
              } else if (credit != null && credit.signum() != 0) {
                amount = credit.abs();
                direction = "CREDIT";
              } else if (amount != null && amount.signum() < 0) {
                amount = amount.abs();
                direction = "DEBIT";
              } else rowIssues.add("Não foi possível determinar débito ou crédito");
              if (date == null) rowIssues.add("Falta a data do movimento");
              movements.add(
                  Inputs.map(
                      "bookedOn",
                      date == null ? null : date.toString(),
                      "description",
                      description,
                      "amount",
                      Money.text(amount),
                      "direction",
                      direction,
                      "currency",
                      "EUR",
                      "kind",
                      "UNCLASSIFIED",
                      "issues",
                      rowIssues));
            }
          } catch (Exception e) {
            rowIssues.add("Valores inválidos na linha");
          }
          if (!rowIssues.isEmpty())
            issues.add(
                sheet.getSheetName()
                    + " linha "
                    + (row.getRowNum() + 1)
                    + ": "
                    + String.join("; ", rowIssues));
        }
        if (kind.equals("HISTORY")) {
          if (expectedReceived != null && expectedReceived.compareTo(receivedSum) != 0)
            issues.add(sheet.getSheetName() + ": total recebido não coincide com as linhas");
          if (expectedPayment != null && expectedPayment.compareTo(paymentSum) != 0)
            issues.add(sheet.getSheetName() + ": total de pagamentos não coincide com as linhas");
          if (expectedClosing != null && expectedClosing.compareTo(running) != 0)
            issues.add(sheet.getSheetName() + ": saldo a transitar não coincide com as linhas");
          sheetResult.put(
              "calculated",
              Inputs.map(
                  "received",
                  Money.text(receivedSum),
                  "payment",
                  Money.text(paymentSum),
                  "closing",
                  Money.text(running)));
        }
      }
    }
    if (kind.equals("HISTORY"))
      return Inputs.map("rows", items, "sheets", sheets, "issues", issues, "year", detectedYear);
    for (var movement : movements)
      movement
          .keySet()
          .retainAll(Set.of("bookedOn", "description", "amount", "direction", "currency", "kind"));
    return Inputs.map(
        "movements", movements, "sheets", sheets, "issues", issues, "validate", issues.isEmpty());
  }

  private static String cell(Row row, Integer column, DataFormatter format) {
    return column == null || row == null ? "" : format.formatCellValue(row.getCell(column)).trim();
  }

  private static BigDecimal numeric(Row row, Integer column, DataFormatter format) {
    String s = cell(row, column, format);
    if (s.isBlank() || s.equals("-")) return null;
    Cell c = row.getCell(column);
    if (c != null
        && (c.getCellType() == CellType.NUMERIC
            || c.getCellType() == CellType.FORMULA
                && c.getCachedFormulaResultType() == CellType.NUMERIC))
      return BigDecimal.valueOf(c.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
    return new BigDecimal(PortugueseDocumentParser.amount(s));
  }

  private static BigDecimal nextAmount(Row row, int col, DataFormatter format) {
    for (int c = col + 1; c < row.getLastCellNum(); c++)
      try {
        var n = numeric(row, c, format);
        if (n != null) return n;
      } catch (Exception ignored) {
      }
    return null;
  }

  private static Integer year(String text) {
    var m = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)").matcher(text);
    return m.find() ? Integer.parseInt(m.group()) : null;
  }

  private static int month(String text) {
    String name = MatchingRules.normalize(text);
    for (int i = 0; i < MONTHS.size(); i++)
      if (name.contains(MONTHS.get(i)) || name.equals(MONTHS.get(i).substring(0, 3))) return i + 1;
    try {
      int n = Integer.parseInt(name);
      return n >= 1 && n <= 12 ? n : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
