package pt.conta.ledger;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.shared.*;

/** Maintains append-only dated effects and derives annual-reset monthly balances. */
@Service
public class LedgerService {
  private final Store store;
  private final JdbcTemplate jdbc;

  private record SourceDocuments(Map<UUID, UUID> values) {}

  public LedgerService(Store store, JdbcTemplate jdbc) {
    this.store = store;
    this.jdbc = jdbc;
  }

  /** Reverses the previous effect and posts a replacement only when its date or value changes. */
  public void replace(
      String type, UUID id, long version, LocalDate date, BigDecimal amount, String reason) {
    var entries = store.all(LedgerEntry.class);
    var reversed = new HashSet<UUID>();
    entries.stream().filter(e -> e.reversesId != null).forEach(e -> reversed.add(e.reversesId));
    var active =
        entries.stream()
            .filter(
                e ->
                    e.sourceType.equals(type)
                        && e.sourceId.equals(id)
                        && e.reversesId == null
                        && !reversed.contains(e.id))
            .toList();
    if (active.size() == 1
        && date != null
        && amount != null
        && active.getFirst().effectiveOn.equals(date)
        && active.getFirst().amount.compareTo(amount) == 0) return;
    for (var old : active) {
      var reversal = new LedgerEntry();
      reversal.sourceType = type;
      reversal.sourceId = id;
      reversal.sourceVersion = version;
      reversal.effectiveOn = old.effectiveOn;
      reversal.amount = old.amount.negate();
      reversal.reversesId = old.id;
      reversal.explanation = "Anulação: " + reason;
      store.add(reversal);
    }
    if (date != null && amount != null && amount.signum() != 0) {
      var entry = new LedgerEntry();
      entry.sourceType = type;
      entry.sourceId = id;
      entry.sourceVersion = version;
      entry.effectiveOn = date;
      entry.amount = amount;
      entry.explanation = reason;
      store.add(entry);
    }
  }

  /** Computes twelve rows from business dates; January deliberately starts at zero. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> year(int year) {
    BigDecimal balance = Money.ZERO;
    var history = store.all(LedgerEntry.class);
    var reversed = new HashSet<UUID>();
    history.stream().filter(e -> e.reversesId != null).forEach(e -> reversed.add(e.reversesId));
    var entries =
        history.stream().filter(e -> e.reversesId == null && !reversed.contains(e.id)).toList();
    List<Map<String, Object>> result = new ArrayList<>();
    for (int month = 1; month <= 12; month++) {
      var period = YearMonth.of(year, month);
      var relevant =
          entries.stream().filter(e -> YearMonth.from(e.effectiveOn).equals(period)).toList();
      BigDecimal change = relevant.stream().map(e -> e.amount).reduce(Money.ZERO, BigDecimal::add);
      BigDecimal opening = balance;
      balance = balance.add(change);
      result.add(
          Map.of(
              "year",
              year,
              "month",
              month,
              "opening",
              Money.text(opening),
              "change",
              Money.text(change),
              "closing",
              Money.text(balance),
              "increases",
              Money.text(
                  relevant.stream()
                      .filter(e -> e.amount.signum() > 0)
                      .map(e -> e.amount)
                      .reduce(Money.ZERO, BigDecimal::add)),
              "reductions",
              Money.text(
                  relevant.stream()
                      .filter(e -> e.amount.signum() < 0)
                      .map(e -> e.amount.negate())
                      .reduce(Money.ZERO, BigDecimal::add)),
              "entries",
              relevant.stream()
                  .sorted(Comparator.comparing(e -> e.effectiveOn))
                  .map(this::entryView)
                  .toList()));
    }
    return result;
  }

  /** Links each currently effective amount to its business record and supporting original. */
  private Map<String, Object> entryView(LedgerEntry entry) {
    UUID documentId =
        store.memo(SourceDocuments.class, this::sourceDocuments).values().get(entry.sourceId);
    return Inputs.map(
        "id",
        entry.id,
        "date",
        entry.effectiveOn,
        "amount",
        Money.text(entry.amount),
        "sourceType",
        entry.sourceType,
        "sourceId",
        entry.sourceId,
        "documentId",
        documentId,
        "explanation",
        entry.explanation,
        "createdAt",
        entry.createdAt);
  }

  /** Loads only identifiers once per transaction, avoiding an original-document query per entry. */
  private SourceDocuments sourceDocuments() {
    var values = new HashMap<UUID, UUID>();
    jdbc.query(
        "select id,document_id from invoice union all select id,document_id from bank_movement"
            + " union all select a.id,m.document_id from allocation a join bank_movement m on"
            + " m.id=a.movement_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> values.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
    return new SourceDocuments(values);
  }

  /** Lists years with any financial effects, even if their net balance is zero. */
  @Transactional(readOnly = true)
  public List<Integer> years() {
    var years = new TreeSet<Integer>(Comparator.reverseOrder());
    years.add(LocalDate.now(ZoneId.of("Europe/Lisbon")).getYear());
    store.all(LedgerEntry.class).forEach(e -> years.add(e.effectiveOn.getYear()));
    return new ArrayList<>(years);
  }
}
