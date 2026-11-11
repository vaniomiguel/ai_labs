package pt.conta.ai;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** The complete provenance of a provider-neutral AI execution. */
@Entity
@Table(name = "ai_execution")
public class AiExecution extends BaseEntity {
  public UUID targetId;
  public long targetVersion;
  public String task;
  public String model;
  public String modelDigest;
  public String promptVersion;
  public String inputHash;
  public long durationMs;
  public String outcome;

  @Column(columnDefinition = "text")
  public String request;

  @Column(columnDefinition = "text")
  public String response;
}
