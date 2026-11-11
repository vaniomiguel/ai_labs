package pt.conta.history;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A resumable import manifest; individual documents keep their own state. */
@Entity
@Table(name = "import_batch")
public class ImportBatch extends BaseEntity {
  public String filename;
  public UUID accountId;
  public String sha256;
  public String status = "PROCESSING";
  public long completedEntries;

  @Column(columnDefinition = "text")
  public String message;
}
