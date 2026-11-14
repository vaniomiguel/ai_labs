package pt.conta.document;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** Immutable archived bytes and mutable processing metadata for one upload. */
@Entity
@Table(name = "source_document")
public class SourceDocument extends BaseEntity {
  @Column(columnDefinition = "text")
  public String fieldProvenance = "{}";

  public String filename;
  public String mediaType;
  public String sha256;
  public long size;
  public String storageKey;
  public String kind;

  @Column(columnDefinition = "text")
  public String sourcePath;

  public UUID accountId;
  public UUID batchId;
  public int pages;

  @Column(columnDefinition = "text")
  public String text = "";

  @Column(columnDefinition = "text")
  public String layout = "{}";

  @Column(columnDefinition = "text")
  public String draft = "{}";

  public String status = "QUEUED";
  public boolean deleted;
}
