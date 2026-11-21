package pt.conta.document;

import jakarta.persistence.*;
import java.util.*;
import pt.conta.shared.BaseEntity;

/** An immutable extraction attempt, including evidence and software versions. */
@Entity
@Table(name = "extraction_attempt")
public class ExtractionAttempt extends BaseEntity {
  public record Prompt(String system, String user) {}

  public UUID documentId;
  public String engine;
  public String outcome;

  @Column(columnDefinition = "text")
  public String result;

  @Column(columnDefinition = "text")
  public String evidence;

  @Column(columnDefinition = "text")
  public String prompts;
}
