package pt.conta.document;

import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import pt.conta.shared.*;

/** Conservative labelled-field parser; ambiguous financial fields remain open for review. */
@Component
public class PortugueseDocumentParser {
  private static final String AMOUNT = "(?:[0-9]{1,3}(?:[. ][0-9]{3})+|[0-9]+)[,.][0-9]{2}";

  /** Parses Portuguese amounts with comma decimal separators, preserving exact cents. */
  public static String amount(String source) {
    String value =
        source.replace("€", "").replace("EUR", "").replaceAll("[\\s\\u00A0\\u202F]", "").trim();
    if (value.contains(",")
        && value.contains(".")
        && value.lastIndexOf('.') > value.lastIndexOf(',')) value = value.replace(",", "");
    else if (value.contains(",")) value = value.replace(".", "").replace(',', '.');
    return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  /** Parses explicit dates; an absent year is supplied only from a known statement period. */
  public static LocalDate date(String text, Integer year) {
    String s = text.trim();
    if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s);
    String[] parts = s.split("[./-]");
    if (parts.length == 2 && year != null)
      return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    if (parts.length != 3) return null;
    int y = Integer.parseInt(parts[2]);
    if (y < 100) y += 2000;
    return LocalDate.of(y, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
  }

  /** Extracts only uniquely labelled invoice values, retaining all unknown fields as null. */
  public Map<String, Object> invoice(String text, String filename) {
    var issues = new ArrayList<String>();
    String total =
        unique(
            text,
            "(?im)^\\s*(?:total\\s+a\\s+pagar|total\\s+da\\s+fatura|total\\s+documento|total\\s+com\\s+iva|valor\\s+total|total)\\s*[:€]*\\s*("
                + AMOUNT
                + ")\\s*(?:€|EUR)?\\s*$",
            issues,
            "total");
    String issued =
        unique(
            text,
            "(?im)^\\s*data(?:\\s+de\\s+emiss[aã]o|\\s+da\\s+fatura)?\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4})",
            issues,
            "data");
    String supplier =
        unique(
            text, "(?im)^\\s*(?:fornecedor|emitente|empresa)\\s*:\\s*(.+)$", issues, "fornecedor");
    String number =
        unique(
            text,
            "(?im)^\\s*(?:fatura|factura|fatura-recibo|invoice)(?:\\s+n[.ºo]*)?\\s*:?\\s*((?:FT|FR|FS)?[A-Z0-9][A-Z0-9"
                + " /._-]*[0-9])\\s*$",
            issues,
            "número");
    String taxId =
        unique(
            text,
            "(?im)^\\s*(?:nif\\s+(?:do\\s+)?(?:fornecedor|emitente)|contribuinte\\s+(?:do\\s+)?(?:fornecedor|emitente))\\s*:\\s*(\\d{9})",
            issues,
            "NIF");
    var categories = new ArrayList<String>();
    String stem = filename.replaceFirst("(?i)\\.pdf$", "").replaceFirst("^\\d{8}_", "");
    if (!stem.equals(filename.replaceFirst("(?i)\\.pdf$", "")))
      for (String category : stem.split("[_;,]+"))
        if (!category.isBlank()) categories.add(category.trim());
    if (Pattern.compile("(?i)descri[cç][aã]o|quantidade|artigos").matcher(text).find())
      issues.add("A tabela de artigos precisa de extração estruturada");
    LocalDate parsed = null;
    try {
      if (issued != null) parsed = date(issued, null);
    } catch (Exception e) {
      issues.add("Data inválida");
    }
    return Inputs.map(
        "issuedOn",
        parsed == null ? null : parsed.toString(),
        "supplier",
        supplier,
        "supplierTaxId",
        taxId,
        "number",
        number,
        "description",
        text,
        "total",
        total == null ? null : amount(total),
        "type",
        pt.conta.reconciliation.MatchingRules.normalize(text).contains("NOTA DE CREDITO")
            ? "CREDIT_NOTE"
            : "INVOICE",
        "currency",
        text.matches("(?s).*(?:USD|GBP|CHF).*") ? "UNKNOWN" : "EUR",
        "categories",
        categories,
        "lines",
        List.of(),
        "issues",
        issues,
        "validate",
        issues.isEmpty());
  }

  /**
   * Parses explicit, dated bank rows and requires a complete header/row count before publication.
   */
  public Map<String, Object> statement(String text) {
    if (normalizedText(text).contains("SALDO CONTABILISTICO APOS MOVIMENTO"))
      return runningBalanceStatement(text);
    var rows = new ArrayList<Map<String, Object>>();
    var issues = new ArrayList<String>();
    var pattern =
        Pattern.compile(
            "(?m)^\\s*(\\d{2}[/-]\\d{2}[/-]\\d{4})\\s+(?:(\\d{2}[/-]\\d{2}[/-]\\d{4})\\s+)?(.+?)\\s+([+-]?"
                + AMOUNT
                + ")\\s*(D|C|DB|CR|DEBITO|CREDITO)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    var matcher = pattern.matcher(text);
    while (matcher.find()) {
      try {
        String raw = matcher.group(4);
        String flag = Objects.toString(matcher.group(5), "").toUpperCase();
        boolean known = raw.startsWith("-") || raw.startsWith("+") || !flag.isBlank();
        if (!known) issues.add("Um movimento não indica débito ou crédito");
        rows.add(
            Inputs.map(
                "bookedOn",
                safeBankDate(matcher.group(1), issues),
                "valueOn",
                matcher.group(2) == null ? null : safeBankDate(matcher.group(2), issues),
                "description",
                matcher.group(3).trim(),
                "amount",
                amount(raw.replaceFirst("^[+-]", "")),
                "direction",
                !known ? null : raw.startsWith("-") || flag.startsWith("D") ? "DEBIT" : "CREDIT",
                "kind",
                "UNCLASSIFIED",
                "currency",
                "EUR"));
      } catch (Exception e) {
        issues.add("Linha bancária inválida: " + matcher.group());
      }
    }
    if (rows.isEmpty()) issues.add("Não foram reconhecidos movimentos bancários");
    long dated =
        Pattern.compile("(?m)^\\s*\\d{2}[/-]\\d{2}[/-]\\d{4}\\s+").matcher(text).results().count();
    if (dated != rows.size()) issues.add("Existem linhas de movimentos por reconhecer");
    return Inputs.map("movements", rows, "issues", issues, "validate", issues.isEmpty());
  }

  /** Reads the movement column of CGD tables, ignoring headline and row balances. */
  private Map<String, Object> runningBalanceStatement(String text) {
    var movements = new ArrayList<Map<String, Object>>();
    var issues = new ArrayList<String>();
    String prefix =
        "^[ \\t]*(\\d{2}-\\d{2}-\\d{4})[ \\t]+(\\d{2}-\\d{2}-\\d{4})[ \\t]+(.+)[ \\t]+([+-]?"
            + AMOUNT
            + ")";
    var withIgnoredColumn = Pattern.compile(prefix + "[ \\t]+\\S[^\\r\\n]*$");
    var withoutIgnoredColumn = Pattern.compile(prefix + "[ \\t]*$");
    var lines = text.split("\\R");
    int dated = 0;
    for (int n = 0; n < lines.length; n++) {
      String line = lines[n];
      if (!line.matches("^[ \\t]*\\d{2}-\\d{2}-\\d{4}[ \\t]+\\d{2}-\\d{2}-\\d{4}.*")) continue;
      dated++;
      var row = withIgnoredColumn.matcher(line);
      if (!row.matches()) {
        row = withoutIgnoredColumn.matcher(line);
        if (!row.matches()) continue;
      }
      String description = row.group(3).trim();
      if (n + 1 < lines.length && lines[n + 1].trim().matches("[A-ZÀ-Ý][A-ZÀ-Ý \\t]{1,50}"))
        description += " " + lines[++n].trim();
      String raw = row.group(4);
      movements.add(
          Inputs.map(
              "bookedOn",
              safeBankDate(row.group(1), issues),
              "description",
              description,
              "amount",
              amount(raw.replaceFirst("^[+-]", "")),
              "direction",
              raw.startsWith("-") ? "DEBIT" : "CREDIT",
              "currency",
              "EUR",
              "kind",
              "UNCLASSIFIED"));
    }
    if (movements.isEmpty() || movements.size() != dated)
      issues.add("Existem linhas de movimentos por reconhecer");
    return Inputs.map("movements", movements, "issues", issues, "validate", issues.isEmpty());
  }

  private String normalizedText(String text) {
    return pt.conta.reconciliation.MatchingRules.normalize(text).replaceAll("\\s+", " ");
  }

  /** Blanks only an unreadable date while retaining the bank row's description and amount. */
  private String safeBankDate(String text, List<String> issues) {
    try {
      return date(text, null).toString();
    } catch (Exception e) {
      issues.add("Data bancária inválida: " + text);
      return null;
    }
  }

  private String unique(String text, String regex, List<String> issues, String field) {
    var values = new LinkedHashSet<String>();
    var m = Pattern.compile(regex).matcher(text);
    while (m.find()) values.add(m.group(1).trim());
    if (values.size() > 1) {
      issues.add("Mais de um candidato para " + field);
      return null;
    }
    return values.stream().findFirst().orElse(null);
  }
}
