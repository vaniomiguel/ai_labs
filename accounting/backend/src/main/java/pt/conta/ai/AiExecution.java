package pt.conta.ai;

import jakarta.persistence.*;
import java.util.*;
import pt.conta.shared.BaseEntity;

/** The complete provenance of a provider-neutral AI execution. */
@Entity
@Table(name = "ai_execution")
public class AiExecution extends BaseEntity {
  public UUID jobId;
  public UUID targetId;
  public long targetVersion;
  public String task;
  public String model;
  public String modelDigest;
  public String promptVersion;
  public String inputHash;
  public long durationMs;
  public String outcome;
  public Integer promptTokens;
  public Integer responseTokens;
  public Integer contextTokens;

  @Enumerated(EnumType.STRING)
  public AiProvider.CapacitySource capacitySource;

  public String doneReason;

  @Enumerated(EnumType.STRING)
  public AiProvider.ContextWarning contextWarning;

  @Column(columnDefinition = "text")
  public String error;

  @Column(columnDefinition = "text")
  public String request;

  @Column(columnDefinition = "text")
  public String response;

  public AiProvider.Usage usage() {
    return contextTokens == null ? null : new AiProvider.Usage(
        promptTokens, responseTokens, contextTokens, capacitySource, doneReason, contextWarning);
  }

  void recordUsage(AiProvider.Usage usage) {
    if (usage == null) return;
    promptTokens = usage.promptTokens();
    responseTokens = usage.responseTokens();
    contextTokens = usage.contextTokens();
    capacitySource = usage.capacitySource();
    doneReason = usage.doneReason();
    contextWarning = usage.warning();
  }
}
