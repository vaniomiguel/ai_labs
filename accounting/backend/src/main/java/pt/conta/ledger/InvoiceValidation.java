package pt.conta.ledger;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;
import pt.conta.invoice.Invoice;
import pt.conta.invoice.InvoiceLine;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.FieldValidationException.Issue;
import pt.conta.shared.Money;
import pt.conta.shared.Store;
import pt.conta.shared.Types.InvoiceType;

/** Shared checks for stored invoices, financial writes and unsaved editor drafts. */
@Component
final class InvoiceValidation {
  static final String ORIGINAL_NOT_FOUND = "ORIGINAL_INVOICE_NOT_FOUND";
  private static final String LINE_SUM_MISMATCH = "LINE_SUM_MISMATCH";
  private final Store store;

  InvoiceValidation(Store store) {
    this.store = store;
  }

  List<Issue> issues(Invoice invoice, List<InvoiceLine> lines, List<Issue> formatIssues) {
    var issues = new ArrayList<>(formatIssues);
    var checks = new ArrayList<Issue>();
    checkHeader(invoice, checks);
    checkLines(lines, checks);
    checkLineSum(invoice, lines, checks);
    checkOriginal(invoice, checks);
    if (isDuplicate(invoice))
      checks.add(new Issue("DUPLICATE_INVOICE", "Existe outra fatura com o mesmo fornecedor e número",
          "number", invoice.supplierTaxId == null ? "supplier" : "supplierTaxId"));
    var invalidFields = new HashSet<String>();
    formatIssues.forEach(issue -> invalidFields.addAll(issue.fields()));
    checks.stream().filter(issue -> applies(issue, invalidFields)).forEach(issues::add);
    return issues;
  }

  private boolean applies(Issue issue, Set<String> invalidFields) {
    if (issue.code().equals(LINE_SUM_MISMATCH) && invalidFields.contains("discount")) return false;
    return Collections.disjoint(issue.fields(), invalidFields);
  }

  private void checkHeader(Invoice invoice, List<Issue> issues) {
    if (invoice.issuedOn == null) issues.add(new Issue("MISSING_DATE", "Falta a data da fatura", "issuedOn"));
    if (invoice.supplier == null) issues.add(new Issue("MISSING_SUPPLIER", "Falta o fornecedor", "supplier"));
    if (invoice.total == null || invoice.total.signum() < 0)
      issues.add(new Issue("INVALID_TOTAL", "Falta um total válido", "total"));
    if (!"EUR".equals(invoice.currency))
      issues.add(new Issue("UNSUPPORTED_CURRENCY", "A moeda precisa de revisão: apenas EUR é suportado", "currency"));
    if (invoice.net != null && invoice.tax != null && invoice.total != null
        && !matchesTotal(invoice.net.add(invoice.tax), invoice.discount, invoice.total))
      issues.add(new Issue("INVOICE_TOTAL_MISMATCH", "Base e IVA não coincidem com o total",
          "net", "tax", "discount", "total"));
  }

  private void checkLines(List<InvoiceLine> lines, List<Issue> issues) {
    for (int index = 0; index < lines.size(); index++) {
      var line = lines.get(index);
      if (line.total == null)
        issues.add(new Issue("MISSING_LINE_TOTAL", "Existem artigos sem total", lineField(index, "total")));
    }
    for (int index = 0; index < lines.size(); index++) {
      var line = lines.get(index);
      if (line.net != null && line.tax != null && line.total != null
          && line.net.add(line.tax).compareTo(line.total) != 0)
        issues.add(new Issue("LINE_TOTAL_MISMATCH", "A base e o IVA de um artigo não coincidem com o seu total",
            lineField(index, "net"), lineField(index, "tax"), lineField(index, "total")));
    }
  }

  private void checkLineSum(Invoice invoice, List<InvoiceLine> lines, List<Issue> issues) {
    if (lines.isEmpty() || invoice.total == null || lines.stream().anyMatch(line -> line.total == null)) return;
    var sum = lines.stream().map(line -> line.total).reduce(Money.ZERO, BigDecimal::add);
    if (!matchesTotal(sum, invoice.discount, invoice.total))
      issues.add(new Issue(LINE_SUM_MISMATCH, "Os artigos não explicam o total da fatura", "total"));
  }

  private void checkOriginal(Invoice invoice, List<Issue> issues) {
    if (invoice.relatedInvoiceId == null) {
      if (invoice.type == InvoiceType.CREDIT_NOTE)
        issues.add(new Issue("MISSING_ORIGINAL_INVOICE", "A nota de crédito precisa da fatura original", "relatedInvoiceId"));
      return;
    }
    if (invoice.relatedInvoiceId.equals(invoice.id)) {
      issues.add(new Issue("SELF_REFERENCING_INVOICE", "Uma fatura não pode referir-se a si própria", "relatedInvoiceId"));
      return;
    }
    var original = store.find(Invoice.class, invoice.relatedInvoiceId).orElse(null);
    if (original == null)
      issues.add(new Issue(ORIGINAL_NOT_FOUND, "Registo não encontrado", "relatedInvoiceId"));
    else if (original.deleted || original.type != InvoiceType.INVOICE || !Objects.equals(original.currency, invoice.currency))
      issues.add(new Issue("INCOMPATIBLE_ORIGINAL_INVOICE", "A fatura original é incompatível com a nota de crédito", "relatedInvoiceId"));
  }

  private boolean isDuplicate(Invoice invoice) {
    if (invoice.number == null) return false;
    var supplier = supplierIdentity(invoice);
    return store.all(Invoice.class).stream().anyMatch(other -> !other.deleted && !other.id.equals(invoice.id)
        && invoice.number.equals(other.number) && Objects.equals(supplierIdentity(other), supplier));
  }

  private String supplierIdentity(Invoice invoice) {
    return invoice.supplierTaxId != null ? invoice.supplierTaxId : MatchingRules.normalize(invoice.supplier);
  }

  private boolean matchesTotal(BigDecimal subtotal, BigDecimal discount, BigDecimal total) {
    return subtotal.compareTo(total) == 0 || subtotal.subtract(Money.orZero(discount)).compareTo(total) == 0;
  }

  private String lineField(int index, String field) {
    return "lines." + index + "." + field;
  }
}
