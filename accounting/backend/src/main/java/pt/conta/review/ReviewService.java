package pt.conta.review;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.review.Alert.Concern;
import pt.conta.shared.*;

/** Manages explicit review issues independently of background processing status. */
@Service
public class ReviewService {
  private final Store store;
  private final AuditService audit;

  public ReviewService(Store store, AuditService audit) {
    this.store = store;
    this.audit = audit;
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
    if (protectedRecord(type, target)) return null;
    boolean aggregate = isRecord(type) || isFinancialSource(type, target);
    var existing = alertsFor(type, target).filter(a -> aggregate || Objects.equals(a.code, code)).findFirst();
    var incoming = messages.stream().filter(message -> !message.isBlank())
        .map(message -> new Concern(code, message.strip())).toList();
    if (incoming.isEmpty()) return null;
    return existing.map(alert -> append(alert, incoming))
        .orElseGet(() -> create(documentId, type, target, incoming));
  }

  public Optional<Alert> openFor(String type, UUID target) {
    return alertsFor(type, target).findFirst();
  }

  /** The caller owns the financial write lock while changing the record's source. */
  public void updateRecordSource(String type, UUID target, UUID source) {
    openFor(type, target).filter(alert -> !Objects.equals(alert.documentId, source)).ifPresent(alert -> {
      alert.documentId = source;
      alert.proposal = null;
    });
  }

  private java.util.stream.Stream<Alert> alertsFor(String type, UUID target) {
    return store.all(Alert.class).stream().filter(a ->
        "OPEN".equals(a.status) && type.equals(a.entityType) && Objects.equals(target, a.entityId));
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
    store.all(Alert.class).stream()
        .filter(
            a ->
                a.status.equals("OPEN")
                    && a.entityType.equals(type)
                    && Objects.equals(a.entityId, target))
        .forEach(
            a -> {
              a.status = "RESOLVED";
              a.resolvedAt = Instant.now();
              a.resolvedBy = audit.actor();
              audit.record("RESOLVE", "alert", a.id, null, Map.of("status", a.status));
            });
  }
}
