package pt.conta.ledger;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;
import pt.conta.invoice.Invoice;
import pt.conta.invoice.InvoiceLine;
import pt.conta.shared.DomainException;
import pt.conta.shared.FieldValidationException;
import pt.conta.shared.FieldValidationException.Issue;
import pt.conta.shared.Money;
import pt.conta.shared.Types.InvoiceType;

/** Parses editor values with the same precision and field paths for preview and save. */
final class InvoiceDraft {
  private static final String INVALID_FIELD_FORMAT = "INVALID_FIELD_FORMAT";
  private final List<Issue> issues = new ArrayList<>();
  private final Invoice invoice;
  private final List<InvoiceLine> lines;
  private DomainException firstFailure;

  InvoiceDraft(Invoice invoice, Map<String, Object> input) {
    this.invoice = invoice;
    readInvoice(input);
    var articles = rows(input, "lines");
    var parsed = new ArrayList<InvoiceLine>(articles.size());
    for (int index = 0; index < articles.size(); index++) {
      parsed.add(readLine(articles.get(index), index));
    }
    lines = List.copyOf(parsed);
  }

  private void readInvoice(Map<String, Object> input) {
    invoice.issuedOn = field("issuedOn", "Data de emissão inválida", () -> date(input, "issuedOn"));
    invoice.number = text(input, "number");
    invoice.supplier = text(input, "supplier");
    invoice.supplierTaxId = text(input, "supplierTaxId");
    invoice.description = text(input, "description");
    invoice.currency = Objects.requireNonNullElse(text(input, "currency"), "EUR");
    invoice.total = money(input, "total", "");
    invoice.net = money(input, "net", "");
    invoice.tax = money(input, "tax", "");
    invoice.discount = money(input, "discount", "");
    invoice.type = field("type", "Tipo de fatura inválido", () -> enumeration(input, "type", InvoiceType.class, InvoiceType.INVOICE));
    invoice.relatedInvoiceId = field("relatedInvoiceId", "Referência da fatura original inválida", () -> uuid(input, "relatedInvoiceId"));
  }

  private InvoiceLine readLine(Map<String, Object> input, int index) {
    var line = new InvoiceLine();
    var prefix = "lines." + index + ".";
    line.invoiceId = invoice.id;
    line.position = index;
    line.description = text(input, "description");
    line.quantity = field(prefix + "quantity", "Quantidade inválida", () -> precise(input.get("quantity")));
    line.unitPrice = field(prefix + "unitPrice", "Preço unitário inválido", () -> precise(input.get("unitPrice")));
    line.net = money(input, "net", prefix);
    line.tax = money(input, "tax", prefix);
    line.discount = money(input, "discount", prefix);
    line.total = money(input, "total", prefix);
    return line;
  }

  private BigDecimal money(Map<String, Object> input, String key, String prefix) {
    return field(prefix + key, "Valor monetário inválido", () -> Money.optional(input.get(key)));
  }

  private BigDecimal precise(Object value) {
    if (value == null || value.toString().isBlank()) return null;
    var amount = new BigDecimal(value.toString()).setScale(6, RoundingMode.UNNECESSARY);
    if (amount.precision() > 19) throw new NumberFormatException("Montante fora do limite");
    return amount;
  }

  private <T> T field(String path, String hint, Supplier<T> read) {
    try {
      return read.get();
    } catch (DomainException failure) {
      invalidField(path, hint, failure);
    } catch (IllegalArgumentException failure) {
      invalidField(path, hint, new DomainException(400, failure.getMessage()));
    } catch (DateTimeParseException | ArithmeticException failure) {
      invalidField(path, hint, DomainException.invalid(hint));
    }
    return null;
  }

  private void invalidField(String path, String hint, DomainException failure) {
    if (firstFailure == null) firstFailure = failure;
    issues.add(new Issue(INVALID_FIELD_FORMAT, hint, path));
  }

  List<InvoiceLine> lines() {
    return lines;
  }

  List<Issue> issues() {
    return List.copyOf(issues);
  }

  void requireReadable(List<Issue> validationIssues) {
    if (firstFailure != null)
      throw new FieldValidationException(firstFailure.status, firstFailure.getMessage(), validationIssues);
  }
}
