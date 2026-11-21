package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pt.conta.ai.AutomaticReviewContext.*;
import pt.conta.document.FieldProtection;
import pt.conta.document.PortugueseDocumentParser;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.DomainException;

/** Checks the model's citations and independently verifies a narrow set of printed facts. */
@Component
class AutomaticReviewEvidence {
  private static final String DATE = "(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./-]\\d{1,2}[./-]\\d{4})";
  private static final String AMOUNT = "(?:[0-9]{1,3}(?:[. ][0-9]{3})+|[0-9]+)[,.][0-9]{2}";
  private static final Pattern ISSUE_DATE = Pattern.compile(
      "(?iu)^\\s*data\\s+(?:de\\s+emiss[aã]o|da\\s+fatura)\\s*:?\\s*" + DATE + "\\s*$");
  private static final Pattern FINAL_TOTAL = Pattern.compile(
      "(?iu)^\\s*(?:total\\s+a\\s+pagar|total\\s+da\\s+fatura|total\\s+documento|total\\s+com\\s+iva)"
          + "\\s*[:€]*\\s*(" + AMOUNT + ")\\s*(?:€|EUR)?\\s*$");
  private static final Pattern MONEY_TOKEN = Pattern.compile(AMOUNT);
  private static final Pattern BALANCE = Pattern.compile("(?iu)\\bsaldo\\b");
  private static final Pattern EXPLICIT_DIRECTION = Pattern.compile(
      "(?iu)[+-]\\s*" + AMOUNT + "|(?<!\\p{L})(?:D|C|DB|CR|D[EÉ]BITO|CR[EÉ]DITO)\\s*$");

  record Finding(Field field, Map<String, Object> detail, boolean verified) {}

  private final FieldProtection protection;
  private final PortugueseDocumentParser parser;

  AutomaticReviewEvidence(FieldProtection protection, PortugueseDocumentParser parser) {
    this.protection = protection;
    this.parser = parser;
  }

  Optional<Finding> check(
      Map<String, Object> concern, Field field, AutomaticReviewRequests.Excerpt excerpt) {
    var source = excerpt.source();
    if (field == null || source == null || !source.id().equals(field.sourceId())) return Optional.empty();
    String recorded = text(concern, "recordedValue");
    String proposed = text(concern, "sourceValue");
    String quote = Objects.toString(concern.get("quote"), "");
    if (recorded == null || proposed == null || quote.trim().length() < 5 || text(concern, "reason") == null
        || !same(field.name(), recorded, field.value()) || same(field.name(), recorded, proposed)
        || !excerpt.text().contains(quote)) return Optional.empty();
    var detail = new LinkedHashMap<>(concern);
    detail.put("page", quotedPage(excerpt, quote));
    boolean verified = source.complete() && verify(field, source, proposed, quote);
    detail.put("verified", verified);
    return Optional.of(new Finding(field, detail, verified));
  }

  private Integer quotedPage(AutomaticReviewRequests.Excerpt excerpt, String quote) {
    int offset = excerpt.text().indexOf(quote);
    if (excerpt.text().indexOf(quote, offset + 1) >= 0) return null;
    int start = offset + excerpt.offset();
    int end = start + quote.length();
    return excerpt.source().pages().stream().filter(page -> page.start() <= start && page.end() >= end)
        .map(Page::number).findFirst().orElse(null);
  }

  boolean same(String field, Object left, Object right) {
    if (Set.of("description", "supplier", "supplierTaxId", "number", "invoiceReference").contains(field))
      return MatchingRules.normalize(Objects.toString(left, ""))
          .equals(MatchingRules.normalize(Objects.toString(right, "")));
    return protection.same(field, left, right);
  }

  private boolean verify(Field field, Source source, String proposed, String quote) {
    var record = field.record();
    if (record == null || record.revision() == null || !Objects.equals(record.documentId(), source.documentId())
        || !same(field.name(), field.value(), record.identity().get(field.name()))
        || field.path().contains(".lines[") || field.path().startsWith("lines[")) return false;
    try {
      return switch (record.revision().kind()) {
        case INVOICE -> "INVOICE".equals(source.kind()) && verifyInvoice(field, source, proposed, quote);
        case MOVEMENT -> "STATEMENT".equals(source.kind())
            && verifyMovement(field, source, proposed, quote);
        default -> false;
      };
    } catch (DomainException | IllegalArgumentException | java.time.DateTimeException e) {
      return false; // Unreadable or ambiguous source values can still be considered by a person.
    }
  }

  private boolean verifyInvoice(Field field, Source source, String proposed, String quote) {
    if (!Set.of("issuedOn", "total").contains(field.name())) return false;
    var identity = field.record().identity();
    String section = invoiceSection(source, text(identity, "number"));
    if (section == null) return false;
    var parsed = parser.invoice(section, "");
    if (parsed.get("issues") instanceof List<?> issues && issues.contains("Mais de um candidato para fornecedor"))
      return false;
    if (text(parsed, "supplier") != null && !same("supplier", parsed.get("supplier"), identity.get("supplier")))
      return false;
    if (field.name().equals("total") && !same("currency", parsed.get("currency"), identity.get("currency")))
      return false;
    if (!same(field.name(), parsed.get(field.name()), proposed)) return false;

    // A printed value must have the right label. A bare date, subtotal or heuristic choice is insufficient.
    Pattern label = field.name().equals("total") ? FINAL_TOTAL : ISSUE_DATE;
    var matches = section.lines().filter(line -> label.matcher(line).matches()).toList();
    if (matches.size() != 1 || !quote.contains(matches.getFirst().strip())) return false;
    var match = label.matcher(matches.getFirst());
    if (!match.matches()) return false;
    String value = field.name().equals("total") ? PortugueseDocumentParser.amount(match.group(1))
        : Objects.toString(PortugueseDocumentParser.date(match.group(1), null), "");
    return same(field.name(), value, proposed);
  }

  private String invoiceSection(Source source, String recordedNumber) {
    if (recordedNumber == null) return null;
    if (source.invoiceCount() == 1) {
      String printed = text(parser.invoice(source.text(), ""), "number");
      return recordedNumber.equalsIgnoreCase(printed) ? source.text() : null;
    }
    // Independent receipts can be verified only when the source separates each identified receipt.
    var sections = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    var pages = source.pages().isEmpty()
        ? Arrays.stream(source.text().split("\\f")).filter(page -> !page.isBlank()).toList()
        : source.pages().stream().map(page -> source.text().substring(page.start(), page.end()))
            .filter(page -> !page.isBlank()).toList();
    if (pages.size() != source.invoiceCount()) return null;
    for (String page : pages) {
      String number = text(parser.invoice(page, ""), "number");
      if (number == null || sections.putIfAbsent(number, page) != null) return null;
    }
    return sections.get(recordedNumber);
  }

  private boolean verifyMovement(Field field, Source source, String proposed, String quote) {
    if (!Set.of("amount", "direction").contains(field.name())) return false;
    var identity = field.record().identity();
    if (text(identity, "bookedOn") == null || text(identity, "description") == null) return false;
    // The worker also checks the persisted statement has only one movement matching this identity.
    boolean runningBalance = MatchingRules.normalize(source.text()).contains("SALDO CONTABILISTICO APOS MOVIMENTO");
    var parse = parser.statement(source.text());
    if (!flag(parse, "validate")) return false;
    var matching = rows(parse, "movements").stream().filter(row -> sameMovement(row, identity)).toList();
    if (matching.size() != 1 || !same(field.name(), matching.getFirst().get(field.name()), proposed)) return false;
    return source.text().lines()
        .filter(line -> !line.isBlank() && quote.contains(line.strip()))
        .anyMatch(line -> supportsBankLine(line, identity, field.name(), proposed, runningBalance));
  }

  private boolean supportsBankLine(
      String line, Map<String, Object> identity, String field, String proposed, boolean runningBalance) {
    if (BALANCE.matcher(line).find() || MONEY_TOKEN.matcher(line).results().count() != (runningBalance ? 2 : 1))
      return false;
    if ((!runningBalance || field.equals("direction")) && !EXPLICIT_DIRECTION.matcher(
        runningBalance ? line.replaceFirst("\\s+[+-]?" + AMOUNT + "\\s*$", "") : line).find()) return false;
    String source = runningBalance ? "SALDO CONTABILISTICO APOS MOVIMENTO\n" + line : line;
    var rows = rows(parser.statement(source), "movements");
    return rows.size() == 1 && sameMovement(rows.getFirst(), identity)
        && same(field, rows.getFirst().get(field), proposed);
  }

  boolean sameMovement(Map<String, Object> left, Map<String, Object> right) {
    return same("bookedOn", left.get("bookedOn"), right.get("bookedOn"))
        && same("description", left.get("description"), right.get("description"));
  }
}
