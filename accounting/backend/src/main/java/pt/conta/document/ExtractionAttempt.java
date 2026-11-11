package pt.conta.document;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An immutable extraction attempt, including evidence and software versions. */
@Entity
@Table(name = "extraction_attempt")
public class ExtractionAttempt extends BaseEntity {
  public UUID documentId;
  public String engine;
  public String outcome;

  @Column(columnDefinition = "text")
  public String result;

  @Column(columnDefinition = "text")
  public String evidence;
}
