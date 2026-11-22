package pt.conta.review;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.JobQueue;
import pt.conta.review.Alert.Concern;
import pt.conta.shared.*;

/** Manages explicit review issues independently of background processing status. */
@Service
public class ReviewService {
  private final Store store;
  private final AuditService audit;
  private final JdbcTemplate jdbc;
  private final JobQueue jobs;

  public ReviewService(Store store, AuditService audit, JdbcTemplate jdbc, JobQueue jobs) {
    this.store = store;
    this.audit = audit;
    this.jdbc = jdbc;
    this.jobs = jobs;
  }

  @Transactional
  public Alert open(UUID documentId, String type, UUID target, String code, String message) {
    return openAll(documentId, type, target, code, List.of(message));
  }

  /** Appends new concerns under the financial lock and skips confirmed records. */
  @Transactional
  public Alert openAll(UUID documentId, String type, UUID target, String code, List<String> messages) {
    if (messages.isEmpty()) return null;
    store.lockFinancialWrites();
    if (protectedRecord(type, target) || repairJobUnavailable(type, target)) return null;
    boolean aggregate = isRecord(type) || isFinancialSource(type, target);
    var dismissed = dismissedConcerns(type, target);
    var incoming = messages.stream().filter(message -> !message.isBlank())
        .map(message -> new Concern(code, message.strip())).filter(concern -> !dismissed.contains(concern)).toList();
    if (incoming.isEmpty()) return null;
    var existing = alertsFor(type, target).filter(Alert::actionable)
        .filter(a -> aggregate || Objects.equals(a.code, code)).findFirst();
    return existing.map(alert -> append(alert, incoming))
        .orElseGet(() -> create(documentId, type, target, incoming));
  }

  @Transactional
  public Alert dismiss(UUID id, long version) {
    store.lockFinancialWrites();
    var alert = canonicalAlert(id, version);
    if (alert.dismissedAt == null) {
      alert.dismissedAt = Instant.now();
      jobs.invalidateAlertRepairs(id);
      audit.record("DISMISS", "alert", id, null, Map.of("dismissedAt", alert.dismissedAt));
      store.flush();
    }
    return alert;
  }

  @Transactional
  public void delete(UUID id, long version) {
    store.lockFinancialWrites();
    var alert = canonicalAlert(id, version);
    if ("OPEN".equals(alert.status) && (isRecord(alert.entityType) || "document".equals(alert.entityType))) {
      jdbc.update("insert into pending_financial_review values (?, ?) on conflict do nothing",
          alert.entityType, alert.entityId);
    }
    var aliases = store.all(Alert.class).stream().filter(alias -> id.equals(alias.mergedInto)).toList();
    store.flush();
    aliases.forEach(this::removeAlert);
    if (!aliases.isEmpty()) store.flush(); // V9 aliases reference the canonical alert.
    removeAlert(alert);
    audit.record("DELETE", "alert", id, null, null);
  }

  private Alert canonicalAlert(UUID id, long version) {
    var alert = store.get(Alert.class, id);
    if (alert.mergedInto != null) throw DomainException.missing();
    store.checkVersion(alert, version);
    return alert;
  }

  private void removeAlert(Alert alert) {
    jobs.invalidateAlertRepairs(alert.id);
    jdbc.update("delete from audit_event where entity_type='alert' and entity_id=?", alert.id);
    store.remove(alert);
  }

  public Optional<Alert> openFor(String type, UUID target) {
    return alertsFor(type, target).filter(a -> "OPEN".equals(a.status)).findFirst();
  }

  public boolean hasPendingReview(String type, UUID target) {
    return openFor(type, target).isPresent() || Boolean.TRUE.equals(jdbc.queryForObject(
        "select exists (select 1 from pending_financial_review where entity_type=? and entity_id=?)",
        Boolean.class, type, target));
  }

  /** The caller owns the financial write lock while changing the record's source. */
  public void updateRecordSource(String type, UUID target, UUID source) {
    alertsFor(type, target).filter(Alert::actionable).findFirst()
        .filter(alert -> !Objects.equals(alert.documentId, source)).ifPresent(alert -> {
          alert.documentId = source;
          alert.proposal = null;
        });
  }

  private Stream<Alert> alertsFor(String type, UUID target) {
    return store.all(Alert.class).stream().filter(a ->
        a.mergedInto == null && type.equals(a.entityType) && Objects.equals(target, a.entityId));
  }

  private Set<Concern> dismissedConcerns(String type, UUID target) {
    var dismissed = new HashSet<Concern>();
    alertsFor(type, target).filter(alert -> alert.dismissedAt != null)
        .forEach(alert -> dismissed.addAll(alert.concerns()));
    return dismissed;
  }

  private boolean repairJobUnavailable(String type, UUID target) {
    return "job".equals(type) && Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from job j left join alert a on a.id=j.target_id
          where j.id=? and j.kind='REPAIR_ALERT' and
            (a.id is null or a.dismissed_at is not null or a.status<>'OPEN' or a.merged_into is not null))
        """, Boolean.class, target));
  }

  public static boolean isRecord(String type) {
    return "invoice".equals(type) || "movement".equals(type);
  }

  public List<String> messages(Object issues) {
    return issues instanceof List<?> entries
        ? entries.stream().filter(String.class::isInstance).map(String.class::cast).toList()
        : List.of();
  }

  public boolean protectedRecord(String type, UUID id) {
    if ("invoice".equals(type)) {
      var invoice = store.get(Invoice.class, id);
      return invoice.manuallyConfirmed || invoice.deleted;
    }
    if ("movement".equals(type)) {
      var movement = store.get(BankMovement.class, id);
      return movement.manuallyConfirmed || movement.deleted;
    }
    return false;
  }

  private boolean isFinancialSource(String type, UUID id) {
    return "document".equals(type)
        && Set.of("INVOICE", "STATEMENT").contains(store.get(SourceDocument.class, id).kind);
  }

  private Alert append(Alert alert, List<Concern> incoming) {
    var entries = new LinkedHashSet<>(alert.concerns());
    var added = incoming.stream().filter(entries::add).toList();
    if (!added.isEmpty()) {
      alert.concerns = Json.write(entries);
      audit.record("ADD_CONCERNS", "alert", alert.id, null, added);
    }
    return alert;
  }

  private Alert create(UUID documentId, String type, UUID target, List<Concern> concerns) {
    var alert = new Alert();
    alert.documentId = documentId;
    alert.entityType = type;
    alert.entityId = target;
    alert.code = concerns.getFirst().code();
    alert.message = concerns.getFirst().message();
    alert.concerns = Json.write(new LinkedHashSet<>(concerns));
    store.add(alert);
    audit.record("OPEN", "alert", alert.id, null,
        Inputs.map("code", alert.code, "message", alert.message, "concerns", alert.concerns()));
    return alert;
  }

  /** Resolves an issue only after its caller has validated and published corrected values. */
  @Transactional
  public void resolveFor(String type, UUID target) {
    store.lockFinancialWrites();
    if (isRecord(type) || "document".equals(type))
      jdbc.update("delete from pending_financial_review where entity_type=? and entity_id=?", type, target);
    alertsFor(type, target).filter(a -> "OPEN".equals(a.status))
        .forEach(
            a -> {
              a.status = "RESOLVED";
              a.resolvedAt = Instant.now();
              a.resolvedBy = audit.actor();
              audit.record("RESOLVE", "alert", a.id, null, Map.of("status", a.status));
            });
  }
}
