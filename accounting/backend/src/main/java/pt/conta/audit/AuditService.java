package pt.conta.audit;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import pt.conta.shared.*;

/** Appends audit entries in the same transaction as the change they describe. */
@Service
public class AuditService {
  private final Store store;

  public AuditService(Store store) {
    this.store = store;
  }

  /** Resolves a signed-in actor or the background-system identity. */
  public String actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? "SYSTEM" : auth.getName();
  }

  /** Persists before/after snapshots without mutating prior audit events. */
  public void record(String action, String type, UUID id, Object before, Object after) {
    var event = new AuditEvent();
    event.actor = actor();
    event.action = action;
    event.entityType = type;
    event.entityId = id;
    event.beforeValue = before == null ? null : Json.write(before);
    event.afterValue = after == null ? null : Json.write(after);
    store.add(event);
  }
}
