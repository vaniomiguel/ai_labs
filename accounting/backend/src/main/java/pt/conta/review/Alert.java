package pt.conta.review;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Json;

/** An actionable review with one or more concerns. */
@Entity
@Table(name = "alert")
public class Alert extends BaseEntity {
  public record Concern(String code, String message) {}

  public UUID documentId;
  public String entityType;
  public UUID entityId;
  public String code;

  @Column(columnDefinition = "text")
  public String message;

  @Column(columnDefinition = "text")
  String concerns = "[]";

  public UUID mergedInto;

  public String status = "OPEN";

  @Column(columnDefinition = "text")
  public String proposal;

  public String resolvedBy;
  public Instant resolvedAt;

  public List<Concern> concerns() {
    var entries = new ArrayList<Concern>();
    Json.read(concerns).forEach(entry -> entries.add(
        new Concern(entry.path("code").asText(), entry.path("message").asText())));
    if (entries.isEmpty() && code != null && message != null) entries.add(new Concern(code, message));
    return List.copyOf(entries);
  }
}
