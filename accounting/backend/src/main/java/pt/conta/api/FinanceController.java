package pt.conta.api;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pt.conta.audit.*;
import pt.conta.bank.*;
import pt.conta.invoice.*;
import pt.conta.ledger.*;
import pt.conta.reconciliation.*;
import pt.conta.shared.*;
import pt.conta.shared.Types.*;

/** Versioned finance API; monetary output remains decimal strings rather than JSON numbers. */
@RestController
@RequestMapping("/api/v1")
@Transactional(readOnly = true)
public class FinanceController {
  private final pt.conta.category.CategoryService categories;
  private final Store store;
  private final BookkeepingService book;
  private final LedgerService ledger;
  private final AuditService audit;
  private final InvoiceRepository invoiceRepository;
  private final MovementRepository movementRepository;
  private final ReconciliationService reconciliation;

  public FinanceController(
      Store store,
      BookkeepingService book,
      LedgerService ledger,
      AuditService audit,
      InvoiceRepository invoiceRepository,
      MovementRepository movementRepository,
      ReconciliationService reconciliation, pt.conta.category.CategoryService categories) {
    this.categories = categories;
    this.store = store;
    this.book = book;
    this.ledger = ledger;
    this.audit = audit;
    this.invoiceRepository = invoiceRepository;
    this.movementRepository = movementRepository;
    this.reconciliation = reconciliation;
  }

  /** Lists invoices in business-date order with combinable filters and stable pagination. */
  @GetMapping("/invoices")
  public Map<String, Object> invoices(@RequestParam Map<String, String> filters) {
    String query = filters.getOrDefault("q", "");
    Set<UUID> searched =
        query.isBlank() ? null : new HashSet<>(invoiceRepository.search(query, literal(query)));
    var criteria = new HashMap<>(filters);
    criteria.remove("q");
    var rows =
        store.all(Invoice.class).stream()
            .filter(i -> !i.deleted && (searched == null || searched.contains(i.id)))
            .map(book::invoiceView)
            .filter(i -> matches(i, criteria, "issuedOn"))
            .filter(
                i ->
                    !filters.containsKey("category")
                        || categories.recordMatches("invoice", (UUID) i.get("id"), filters.get("category")))
            .sorted(Comparator.comparing(i -> Objects.toString(i.get("issuedOn"), "9999")))
            .toList();
    return page(rows, filters);
  }

  /** Returns a complete editable invoice. */
  @GetMapping("/invoices/{id}")
  public Map<String, Object> invoice(@PathVariable UUID id) {
    return book.invoiceView(store.get(Invoice.class, id));
  }

  /** Creates an invoice, optionally already validated by the owner. */
  @PostMapping("/invoices")
  @Transactional
  public Map<String, Object> createInvoice(@RequestBody Map<String, Object> body) {
    return book.saveInvoice(null, body);
  }

  /** Applies an optimistic invoice edit through financial integrity checks. */
  @PutMapping("/invoices/{id}")
  @Transactional
  public Map<String, Object> editInvoice(
      @PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return book.saveInvoice(id, body);
  }

  /** Soft-deletes an invoice and reverses its effects. */
  @DeleteMapping("/invoices/{id}")
  @Transactional
  public void deleteInvoice(@PathVariable UUID id, @RequestParam long version) {
    book.delete("invoice", id, version);
  }

  /** Lists bank movements without inferring payment status from debit/credit alone. */
  @GetMapping("/movements")
  public Map<String, Object> movements(@RequestParam Map<String, String> filters) {
    String query = filters.getOrDefault("q", "");
    Set<UUID> searched =
        query.isBlank() ? null : new HashSet<>(movementRepository.search(query, literal(query)));
    var criteria = new HashMap<>(filters);
    criteria.remove("q");
    var rows =
        store.all(BankMovement.class).stream()
            .filter(m -> !m.deleted && (searched == null || searched.contains(m.id)))
            .map(book::movementView)
            .filter(m -> matches(m, criteria, "bookedOn"))
            .filter(
                m ->
                    !filters.containsKey("accountId")
                        || Objects.toString(m.get("accountId"), "")
                            .equals(filters.get("accountId")))
            .filter(
                m ->
                    !filters.containsKey("direction")
                        || Objects.equals(m.get("direction"), filters.get("direction")))
            .filter(
                m ->
                    !filters.containsKey("time")
                        || Objects.toString(m.get("occurredAt"), "")
                            .startsWith(filters.get("time")))
            .filter(m -> !filters.containsKey("category") || categories.recordMatches("movement", (UUID) m.get("id"), filters.get("category")))
            .filter(m -> amountMatches(m, filters))
            .sorted(Comparator.comparing(m -> Objects.toString(m.get("bookedOn"), "9999")))
            .toList();
    return page(rows, filters);
  }

  /** Returns one movement with its source identities. */
  @GetMapping("/movements/{id}")
  public Map<String, Object> movement(@PathVariable UUID id) {
    return book.movementView(store.get(BankMovement.class, id));
  }

  /** Creates a bank movement with an optional supporting document. */
  @PostMapping("/movements")
  @Transactional
  public Map<String, Object> createMovement(@RequestBody Map<String, Object> body) {
    return book.saveMovement(null, body);
  }

  /** Edits and optionally validates a movement. */
  @PutMapping("/movements/{id}")
  @Transactional
  public Map<String, Object> editMovement(
      @PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return book.saveMovement(id, body);
  }

  /** Soft-deletes a movement and detaches affected payments. */
  @DeleteMapping("/movements/{id}")
  @Transactional
  public void deleteMovement(@PathVariable UUID id, @RequestParam long version) {
    book.delete("movement", id, version);
  }

  /** Returns active allocations, including partial payments and reversals. */
  @GetMapping("/allocations")
  public List<Map<String, Object>> allocations() {
    return store.all(Allocation.class).stream()
        .filter(a -> a.active)
        .map(book::allocationView)
        .toList();
  }

  @GetMapping("/reconciliation/entries")
  public ReconciliationService.Page entries(@RequestParam Map<String, String> filters) {
    return reconciliation.entries(filters);
  }

  @PostMapping("/reconciliation/exclusions")
  @Transactional
  public void exclusions(@Valid @RequestBody ExclusionRequest request) {
    book.exclude(request);
  }

  /** Confirms a user-selected monetary association. */
  @PostMapping("/allocations")
  @Transactional
  public Map<String, Object> allocate(@RequestBody Map<String, Object> body) {
    return book.allocate(body);
  }

  /** Undoes an association without deleting its evidence. */
  @DeleteMapping("/allocations/{id}")
  @Transactional
  public void unallocate(@PathVariable UUID id) {
    book.unallocate(id);
  }

  /** Returns explainable matching suggestions. */
  @GetMapping("/reconciliation/candidates")
  public List<Map<String, Object>> candidates() {
    return book.candidates();
  }

  /** Returns only AI rankings whose complete invoice/candidate snapshot is still current. */
  @GetMapping("/reconciliation/ai-suggestions")
  public List<Map<String, Object>> suggestions() {
    var candidates = book.candidates();
    var seen = new HashSet<UUID>();
    var result = new ArrayList<Map<String, Object>>();
    for (var e :
        store.all(pt.conta.ai.AiExecution.class).stream()
            .filter(e -> e.task.equals("reconciliation") && e.outcome.equals("VALID"))
            .sorted(Comparator.comparing((pt.conta.ai.AiExecution e) -> e.createdAt).reversed())
            .toList()) {
      if (seen.contains(e.targetId)) continue;
      var invoice = store.get(Invoice.class, e.targetId);
      if (invoice.version != e.targetVersion || !BookkeepingService.eligible(invoice)) continue;
      String fingerprint = Hashes.sha256(Json.write(book.candidateContext(invoice.id, candidates)));
      if (!fingerprint.equals(Json.read(e.request).path("candidateFingerprint").asText())) continue;
      var response = Json.object(e.response);
      var allowed =
          candidates.stream()
              .filter(c -> c.get("invoiceId").equals(invoice.id))
              .map(c -> c.get("movementId").toString())
              .toList();
      if (rows(response, "rankedCandidates").stream()
          .anyMatch(r -> !allowed.contains(r.get("movementId")))) continue;
      seen.add(invoice.id);
      result.add(
          map(
              "invoiceId",
              invoice.id,
              "model",
              e.model,
              "summary",
              response.get("summary"),
              "rankedCandidates",
              response.get("rankedCandidates")));
    }
    return result;
  }

  /** Summarizes an annual or monthly period without doing decimal arithmetic in the browser. */
  @GetMapping("/reconciliation/summary")
  public Map<String, Object> summary(
      @RequestParam int year, @RequestParam(required = false) Integer month) {
    if (month != null && (month < 1 || month > 12)) throw DomainException.invalid("Mês inválido");
    var row = ledger.year(year).get(month == null ? 11 : month - 1);
    var outstanding =
        store.all(Invoice.class).stream()
            .filter(BookkeepingService::eligible)
            .filter(
                i ->
                    i.issuedOn.getYear() == year
                        && (month == null || i.issuedOn.getMonthValue() == month))
            .map(
                i ->
                    book.remaining(i)
                        .multiply(
                            i.type == InvoiceType.CREDIT_NOTE
                                ? BigDecimal.ONE.negate()
                                : BigDecimal.ONE))
            .reduce(Money.ZERO, BigDecimal::add);
    return map(
        "closing",
        row.get("closing"),
        "opening",
        month == null ? "0.00" : row.get("opening"),
        "change",
        month == null ? row.get("closing") : row.get("change"),
        "outstanding",
        Money.text(outstanding));
  }

  /** Reprocesses pending matches while preserving valid manual decisions. */
  @PostMapping("/reconciliation/run")
  @Transactional
  public void reconcile() {
    store.lockFinancialWrites();
    book.synchronize();
    book.automatic();
  }

  /** Lists all years represented in the ledger. */
  @GetMapping("/balances")
  public List<Map<String, Object>> balances() {
    return ledger.years().stream().map(y -> map("year", y, "months", ledger.year(y))).toList();
  }

  /** Returns the twelve cumulative balances of one year. */
  @GetMapping("/balances/{year}")
  public List<Map<String, Object>> balance(@PathVariable int year) {
    return ledger.year(year);
  }

  /** Lists company bank accounts. */
  @GetMapping("/accounts")
  public List<Map<String, Object>> accounts() {
    return store.all(BankAccount.class).stream()
        .filter(a -> !a.deleted)
        .map(
            a ->
                map(
                    "id",
                    a.id,
                    "version",
                    a.version,
                    "name",
                    a.name,
                    "iban",
                    a.iban,
                    "currency",
                    a.currency))
        .toList();
  }

  /** Adds or edits one EUR account. */
  @PostMapping("/accounts")
  @Transactional
  public Map<String, Object> account(@RequestBody Map<String, Object> body) {
    store.lockFinancialWrites();
    UUID id = uuid(body, "id");
    var a = id == null ? store.add(new BankAccount()) : store.get(BankAccount.class, id);
    if (id != null) store.checkVersion(a, version(body));
    a.name = text(body, "name");
    a.iban = text(body, "iban");
    if (a.name == null) throw DomainException.invalid("Indica o nome da conta");
    audit.record("SAVE", "account", a.id, null, body);
    store.flush();
    return map(
        "id", a.id, "version", a.version, "name", a.name, "iban", a.iban, "currency", a.currency);
  }

  @GetMapping("/categories")
  public List<Map<String, Object>> categories(@RequestParam(defaultValue = "false") boolean archived) {
    return categories.catalog(archived);
  }

  @PostMapping("/categories")
  @Transactional
  public Map<String, Object> category(@RequestBody Map<String, Object> body) {
    return categories.save(body);
  }

  @DeleteMapping("/categories/{id}")
  @Transactional
  public void deleteCategory(@PathVariable UUID id, @RequestParam long version,
      @RequestParam(defaultValue = "false") boolean permanently) {
    categories.archive(id, version, permanently);
  }

  private boolean matches(Map<String, Object> row, Map<String, String> filters, String dateKey) {
    var date = row.get(dateKey) == null ? null : LocalDate.parse(row.get(dateKey).toString());
    if (filters.containsKey("year")
        && (date == null || date.getYear() != Integer.parseInt(filters.get("year")))) return false;
    if (filters.containsKey("month")
        && (date == null || date.getMonthValue() != Integer.parseInt(filters.get("month"))))
      return false;
    if (filters.containsKey("day")
        && (date == null || date.getDayOfMonth() != Integer.parseInt(filters.get("day"))))
      return false;
    if (filters.containsKey("from")
        && (date == null || date.isBefore(LocalDate.parse(filters.get("from"))))) return false;
    if (filters.containsKey("to")
        && (date == null || date.isAfter(LocalDate.parse(filters.get("to"))))) return false;
    if (filters.containsKey("status")
        && !row.get("reviewStatus").toString().equals(filters.get("status"))) return false;
    if (filters.containsKey("q")) {
      String haystack = MatchingRules.normalize(row.toString());
      String needle = MatchingRules.normalize(filters.get("q"));
      if (!haystack.contains(needle)) {
        UUID doc = (UUID) row.get("documentId");
        if (doc == null
            || !MatchingRules.normalize(store.get(pt.conta.document.SourceDocument.class, doc).text)
                .contains(needle)) return false;
      }
    }
    return true;
  }

  private boolean amountMatches(Map<String, Object> row, Map<String, String> filters) {
    if (row.get("amount") == null)
      return !filters.containsKey("min") && !filters.containsKey("max");
    var amount = new BigDecimal(row.get("amount").toString());
    return (!filters.containsKey("min")
            || amount.compareTo(new BigDecimal(filters.get("min"))) >= 0)
        && (!filters.containsKey("max")
            || amount.compareTo(new BigDecimal(filters.get("max"))) <= 0);
  }

  private Map<String, Object> page(List<Map<String, Object>> rows, Map<String, String> filters) {
    int page = Math.max(0, Integer.parseInt(filters.getOrDefault("page", "0"))),
        size = Math.clamp(Integer.parseInt(filters.getOrDefault("size", "100")), 1, 500);
    return map(
        "items",
        rows.stream().skip((long) page * size).limit(size).toList(),
        "total",
        rows.size(),
        "page",
        page,
        "size",
        size);
  }

  private String literal(String query) {
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
