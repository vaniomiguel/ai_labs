package pt.conta.review;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
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

  /** Opens one issue per active code and target, preserving the original explanation. */
  @Transactional
  public Alert open(UUID documentId, String type, UUID target, String code, String message) {
    var existing =
        store.all(Alert.class).stream()
            .filter(
                a ->
                    a.status.equals("OPEN")
                        && Objects.equals(a.entityId, target)
                        && a.code.equals(code))
            .findFirst();
    if (existing.isPresent()) return existing.get();
    var alert = new Alert();
    alert.documentId = documentId;
    alert.entityType = type;
    alert.entityId = target;
    alert.code = code;
    alert.message = message;
    store.add(alert);
    audit.record("OPEN", "alert", alert.id, null, Inputs.map("code", code, "message", message));
    return alert;
  }

  /** Resolves an issue only after its caller has validated and published corrected values. */
  @Transactional
  public void resolveFor(String type, UUID target) {
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
