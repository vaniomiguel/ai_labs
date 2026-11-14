package pt.conta.reconciliation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.bank.*;
import pt.conta.invoice.*;
import pt.conta.ledger.BookkeepingService;
import pt.conta.shared.*;
import pt.conta.shared.Types.*;

/** Builds complete connected groups before filtering and pagination; no money is counted twice. */
@Service
@Transactional(readOnly = true)
public class ReconciliationService {
  public record Differences(boolean dates, boolean amounts) {}
  public record Entry(
      String id, String status, boolean excluded, boolean pendingReview,
      List<Map<String, Object>> invoices, List<Map<String, Object>> movements,
      List<Map<String, Object>> allocations, Differences differences,
      String invoiceTotal, String debitTotal, String creditTotal,
      String balanceImpact, String periodImpact, boolean spansPeriods,
      LocalDate firstDate, LocalDate lastDate) {}
  public record Page(List<Entry> items, int total, int page, int size) {}

  private final pt.conta.category.CategoryService categories;
  private final Store store;
  private final BookkeepingService book;
  private final InvoiceRepository invoices;
  private final MovementRepository movements;

  public ReconciliationService(Store store, BookkeepingService book,
      InvoiceRepository invoices, MovementRepository movements, pt.conta.category.CategoryService categories) {
    this.categories = categories;
    this.store = store;
    this.book = book;
    this.invoices = invoices;
    this.movements = movements;
  }

  public Page entries(Map<String, String> requested) {
    var filters = new HashMap<String, String>();
    requested.forEach((key, value) -> { if (value != null && !value.isBlank()) filters.put(key, value); });
    var invoiceMap = new LinkedHashMap<UUID, Invoice>();
    var movementMap = new LinkedHashMap<UUID, BankMovement>();
    store.all(Invoice.class).stream().filter(i -> !i.deleted).forEach(i -> invoiceMap.put(i.id, i));
    store.all(BankMovement.class).stream().filter(m -> !m.deleted).forEach(m -> movementMap.put(m.id, m));
    var parent = new HashMap<UUID, UUID>();
    invoiceMap.keySet().forEach(id -> parent.put(id, id));
    movementMap.keySet().forEach(id -> parent.put(id, id));
    var links = store.all(Allocation.class).stream()
        .filter(a -> a.active && invoiceMap.containsKey(a.invoiceId) && movementMap.containsKey(a.movementId))
        .filter(a -> BookkeepingService.eligible(invoiceMap.get(a.invoiceId))
            && BookkeepingService.eligible(movementMap.get(a.movementId)))
        .toList();
    for (var a : links) parent.put(root(parent, a.movementId), root(parent, a.invoiceId));
    var groupLinks = new HashMap<UUID, List<Allocation>>();
    for (var a : links) groupLinks.computeIfAbsent(root(parent, a.invoiceId), k -> new ArrayList<>()).add(a);
    var groups = new LinkedHashMap<UUID, List<UUID>>();
    parent.keySet().stream().sorted().forEach(id -> groups.computeIfAbsent(root(parent, id), k -> new ArrayList<>()).add(id));
    var period = period(filters);
    String query = filters.getOrDefault("q", "");
    var search = new HashSet<UUID>();
    if (!query.isBlank()) {
      String literal = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      search.addAll(invoices.search(query, literal));
      search.addAll(movements.search(query, literal));
    }
    var rows = new ArrayList<Entry>();
    for (var ids : groups.values()) {
      var ii = ids.stream().filter(invoiceMap::containsKey).map(invoiceMap::get).toList();
      var mm = ids.stream().filter(movementMap::containsKey).map(movementMap::get).toList();
      var aa = groupLinks.getOrDefault(root(parent, ids.getFirst()), List.of());
      boolean pendingReview = ii.stream().anyMatch(i -> i.reviewStatus == ReviewStatus.OPEN)
          || mm.stream().anyMatch(m -> m.reviewStatus == ReviewStatus.OPEN);
      var dates = new ArrayList<LocalDate>();
      ii.stream().map(i -> i.issuedOn).filter(Objects::nonNull).forEach(dates::add);
      mm.stream().map(m -> m.bookedOn).filter(Objects::nonNull).forEach(dates::add);
      if (dates.isEmpty() ? hasPeriod(filters) && !pendingReview : dates.stream().noneMatch(period)) continue;
      if (!query.isBlank() && ids.stream().noneMatch(search::contains)) continue;
      var iv = ii.stream().map(book::invoiceView).toList();
      var mv = mm.stream().map(book::movementView).toList();
      var records = new ArrayList<Map<String, Object>>(iv);
      records.addAll(mv);
      if (filters.containsKey("status") && records.stream().noneMatch(r -> r.get("reviewStatus").toString().equals(filters.get("status")))) continue;
      if (filters.containsKey("category") && ii.stream().noneMatch(i -> categories.recordMatches("invoice", i.id, filters.get("category")))
          && mm.stream().noneMatch(m -> categories.recordMatches("movement", m.id, filters.get("category")))) continue;
      if (filters.containsKey("accountId") && mm.stream().noneMatch(m -> Objects.toString(m.accountId, "").equals(filters.get("accountId")))) continue;
      if (filters.containsKey("direction") && mm.stream().noneMatch(m -> Objects.toString(m.direction, "").equals(filters.get("direction")))) continue;
      if (filters.containsKey("time") && mm.stream().noneMatch(m -> Objects.toString(m.occurredAt, "").startsWith(filters.get("time")))) continue;
      if ((filters.containsKey("min") || filters.containsKey("max"))
          && records.stream().noneMatch(r -> amountMatches(r.get(r.containsKey("total") ? "total" : "amount"), filters))) continue;
      boolean excluded = ii.stream().anyMatch(i -> i.excluded) || mm.stream().anyMatch(m -> m.excluded);
      if ("active".equals(filters.get("participation")) && excluded
          || "excluded".equals(filters.get("participation")) && !excluded) continue;
      boolean pending = !excluded && (ii.stream().anyMatch(i -> !BookkeepingService.eligible(i))
          || mm.stream().anyMatch(m -> !BookkeepingService.eligible(m)));
      BigDecimal invoiceTotal = ii.stream().map(i -> Money.orZero(i.total).multiply(i.type == InvoiceType.CREDIT_NOTE ? BigDecimal.ONE.negate() : BigDecimal.ONE)).reduce(Money.ZERO, BigDecimal::add);
      BigDecimal debitTotal = mm.stream().filter(m -> m.direction == Direction.DEBIT).map(m -> Money.orZero(m.amount)).reduce(Money.ZERO, BigDecimal::add);
      BigDecimal creditTotal = mm.stream().filter(m -> m.direction == Direction.CREDIT).map(m -> Money.orZero(m.amount)).reduce(Money.ZERO, BigDecimal::add);
      BigDecimal balance = Money.ZERO, periodEffect = Money.ZERO;
      for (var i : ii) {
        var effect = Money.orZero(BookkeepingService.invoiceEffect(i));
        balance = balance.add(effect);
        if (period.test(i.issuedOn)) periodEffect = periodEffect.add(effect);
      }
      for (var m : mm) {
        var effect = Money.orZero(BookkeepingService.movementEffect(m));
        balance = balance.add(effect);
        if (period.test(m.bookedOn)) periodEffect = periodEffect.add(effect);
      }
      boolean differentDates = aa.stream().anyMatch(a -> !Objects.equals(invoiceMap.get(a.invoiceId).issuedOn, movementMap.get(a.movementId).bookedOn));
      boolean differentAmounts = !aa.isEmpty() && balance.signum() != 0;
      String status = excluded ? "EXCLUDED" : pending || pendingReview ? "PENDING_REVIEW"
          : aa.isEmpty() ? ii.isEmpty() ? "UNMATCHED_MOVEMENT" : "UNMATCHED_INVOICE"
          : differentDates || differentAmounts ? "MATCHED_DIFFERENCES" : "MATCHED";
      var identity = ids.stream().map(UUID::toString).sorted().reduce("", (a, b) -> a + ":" + b);
      dates.sort(Comparator.naturalOrder());
      rows.add(new Entry(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), status, excluded, pendingReview,
          iv, mv, aa.stream().map(book::allocationView).toList(), new Differences(differentDates, differentAmounts),
          Money.text(invoiceTotal), Money.text(debitTotal), Money.text(creditTotal),
          pending || pendingReview ? null : Money.text(balance), pending || pendingReview ? null : Money.text(periodEffect),
          hasPeriod(filters) && dates.stream().anyMatch(period.negate()),
          dates.isEmpty() ? null : dates.getFirst(), dates.isEmpty() ? null : dates.getLast()));
    }
    rows.sort(Comparator.comparing((Entry e) -> !(e.pendingReview() && e.firstDate()==null))
        .thenComparing(Entry::firstDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Entry::id));
    int page = Math.max(0, integer(filters, "page", 0)), size = Math.clamp(integer(filters, "size", 100), 1, 500);
    return new Page(rows.stream().skip((long) page * size).limit(size).toList(), rows.size(), page, size);
  }

  private UUID root(Map<UUID, UUID> parent, UUID id) {
    UUID current = id;
    while (!parent.get(current).equals(current)) current = parent.get(current);
    while (!id.equals(current)) { UUID next = parent.get(id); parent.put(id, current); id = next; }
    return current;
  }

  private Predicate<LocalDate> period(Map<String, String> f) {
    Integer year = f.containsKey("year") ? integer(f, "year", 0) : null;
    Integer month = f.containsKey("month") ? integer(f, "month", 0) : null;
    Integer day = f.containsKey("day") ? integer(f, "day", 0) : null;
    if (month != null && (month < 1 || month > 12) || day != null && (day < 1 || day > 31))
      throw DomainException.invalid("Período inválido");
    LocalDate from, to;
    try {
      from = f.containsKey("from") ? LocalDate.parse(f.get("from")) : null;
      to = f.containsKey("to") ? LocalDate.parse(f.get("to")) : null;
    } catch (java.time.format.DateTimeParseException e) { throw DomainException.invalid("Data inválida"); }
    if (from != null && to != null && from.isAfter(to)) throw DomainException.invalid("Intervalo inválido");
    return d -> d != null && (year == null || d.getYear() == year)
        && (month == null || d.getMonthValue() == month) && (day == null || d.getDayOfMonth() == day)
        && (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to));
  }

  private boolean hasPeriod(Map<String, String> f) {
    return List.of("year", "month", "day", "from", "to").stream().anyMatch(f::containsKey);
  }

  private int integer(Map<String, String> f, String key, int fallback) {
    try { return Integer.parseInt(f.getOrDefault(key, Integer.toString(fallback))); }
    catch (NumberFormatException e) { throw DomainException.invalid("Filtro numérico inválido: " + key); }
  }

  private boolean amountMatches(Object value, Map<String, String> f) {
    BigDecimal amount = Money.optional(value);
    return amount != null && (!f.containsKey("min") || amount.compareTo(Money.optional(f.get("min"))) >= 0)
        && (!f.containsKey("max") || amount.compareTo(Money.optional(f.get("max"))) <= 0);
  }
}
