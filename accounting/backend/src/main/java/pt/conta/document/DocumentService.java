package pt.conta.document;

import java.io.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankAccount;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.*;

/** Archives uploads and atomically schedules their durable processing. */
@Service
public class DocumentService {
  private final Store store;
  private final DocumentStorage storage;
  private final AccountingProperties properties;
  private final JobQueue jobs;
  private final AuditService audit;
  private final DocumentRepository repository;

  public DocumentService(
      Store store,
      DocumentStorage storage,
      AccountingProperties properties,
      JobQueue jobs,
      AuditService audit,
      DocumentRepository repository) {
    this.store = store;
    this.storage = storage;
    this.properties = properties;
    this.jobs = jobs;
    this.audit = audit;
    this.repository = repository;
  }

  /** Archives a document, reuses exact duplicates and never trusts a filename as a storage path. */
  @Transactional
  public Map<String, Object> upload(
      InputStream input, String filename, String kind, UUID account, UUID batch, String sourcePath)
      throws IOException {
    if (!Set.of("INVOICE", "STATEMENT", "HISTORY", "ARCHIVE", "SUPPORT").contains(kind))
      throw DomainException.invalid("Tipo de documento desconhecido");
    String lower = filename.toLowerCase(Locale.ROOT);
    if (!lower.matches(".*\\.(pdf|xlsx|xls|zip)$"))
      throw DomainException.invalid("Usa PDF, XLSX, XLS ou ZIP");
    if (kind.equals("INVOICE") && !lower.endsWith(".pdf"))
      throw DomainException.invalid("As faturas importadas devem estar em PDF");
    if (kind.equals("HISTORY") && !lower.matches(".*\\.(xlsx|xls)$"))
      throw DomainException.invalid("O histórico deve ser um ficheiro Excel");
    if (kind.equals("ARCHIVE") != lower.endsWith(".zip"))
      throw DomainException.invalid("Um arquivo deve ser ZIP");
    if (Set.of("STATEMENT", "ARCHIVE").contains(kind) && account == null)
      throw DomainException.invalid("Seleciona a conta bancária");
    if (account != null) store.get(BankAccount.class, account);
    var file = storage.put(input, properties.maxDocumentBytes());
    store.lockFinancialWrites();
    var duplicate = repository.findFirstBySha256AndKindAndAccountId(file.sha256(), kind, account);
    if (duplicate.isPresent()) {
      storage.discard(file.key());
      return view(duplicate.get());
    }
    var doc = new SourceDocument();
    doc.filename = filename.replaceAll("[\\r\\n]", "");
    doc.kind = kind;
    doc.sha256 = file.sha256();
    doc.storageKey = file.key();
    doc.size = file.size();
    doc.accountId = account;
    doc.batchId = batch;
    doc.sourcePath = sourcePath;
    doc.mediaType =
        lower.endsWith(".pdf")
            ? "application/pdf"
            : lower.endsWith(".zip")
                ? "application/zip"
                : lower.endsWith(".xlsx")
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    : "application/vnd.ms-excel";
    store.add(doc);
    store.flush();
    if (!kind.equals("SUPPORT"))
      jobs.enqueue(kind.equals("ARCHIVE") ? "IMPORT_ZIP" : "EXTRACT", doc.id, doc.version, "{}");
    else doc.status = "DONE";
    audit.record("UPLOAD", "document", doc.id, null, view(doc));
    return view(doc);
  }

  /** Returns public metadata and editable extracted content without exposing storage paths. */
  public Map<String, Object> view(SourceDocument d) {
    return Inputs.map(
        "id",
        d.id,
        "version",
        d.version,
        "filename",
        d.filename,
        "kind",
        d.kind,
        "mediaType",
        d.mediaType,
        "sha256",
        d.sha256,
        "size",
        d.size,
        "pages",
        d.pages,
        "status",
        d.status,
        "accountId",
        d.accountId,
        "batchId",
        d.batchId,
        "sourcePath",
        d.sourcePath,
        "createdAt",
        d.createdAt,
        "updatedAt",
        d.updatedAt,
        "text",
        d.text,
        "layout",
        Json.value(d.layout),
        "draft",
        Json.value(d.draft));
  }

  /** Lists upload metadata while keeping large extraction payloads out of the database result. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> list() {
    return repository.metadata().stream()
        .map(
            d ->
                Inputs.map(
                    "id",
                    d.getId(),
                    "version",
                    d.getVersion(),
                    "filename",
                    d.getFilename(),
                    "kind",
                    d.getKind(),
                    "mediaType",
                    d.getMediaType(),
                    "sha256",
                    d.getSha256(),
                    "size",
                    d.getSize(),
                    "pages",
                    d.getPages(),
                    "status",
                    d.getStatus(),
                    "accountId",
                    d.getAccountId(),
                    "batchId",
                    d.getBatchId(),
                    "sourcePath",
                    d.getSourcePath(),
                    "createdAt",
                    d.getCreatedAt(),
                    "updatedAt",
                    d.getUpdatedAt()))
        .toList();
  }
}
