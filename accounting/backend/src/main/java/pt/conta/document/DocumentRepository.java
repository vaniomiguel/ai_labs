package pt.conta.document;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

/** Reads hashes and metadata without loading every historical PDF's text and layout into memory. */
public interface DocumentRepository extends JpaRepository<SourceDocument, UUID> {
  /** Finds an exact upload duplicate, including the intended bank-account scope. */
  Optional<SourceDocument> findFirstBySha256AndKindAndAccountId(
      String sha256, String kind, UUID accountId);

  /** Projects the lightweight columns needed by the extraction list. */
  @Query(
      "select d.id as id,d.version as version,d.filename as filename,d.kind as kind,d.mediaType as"
          + " mediaType,d.sha256 as sha256,d.size as size,d.pages as pages,d.status as"
          + " status,d.accountId as accountId,d.batchId as batchId,d.sourcePath as"
          + " sourcePath,d.createdAt as createdAt,d.updatedAt as updatedAt from SourceDocument d"
          + " where not d.deleted order by d.createdAt desc")
  List<Metadata> metadata();

  /** Closed projection deliberately excludes extracted text, raw drafts and binary storage keys. */
  interface Metadata {
    UUID getId();

    long getVersion();

    String getFilename();

    String getKind();

    String getMediaType();

    String getSha256();

    long getSize();

    int getPages();

    String getStatus();

    UUID getAccountId();

    UUID getBatchId();

    String getSourcePath();

    Instant getCreatedAt();

    Instant getUpdatedAt();
  }
}
