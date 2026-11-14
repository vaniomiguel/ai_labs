package pt.conta.api;

import static pt.conta.shared.Inputs.*;

import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pt.conta.audit.AuditEvent;
import pt.conta.document.*;
import pt.conta.history.*;
import pt.conta.jobs.*;
import pt.conta.review.*;
import pt.conta.shared.*;

/** Original-document access, extraction review, historical imports and durable job inspection. */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {
  private final Store store;
  private final DocumentService documents;
  private final DocumentStorage storage;
  private final DocumentPublisher publisher;
  private final JobQueue jobs;
  private final RepairService repairs;
  private final HistoryService history;
  private final pt.conta.ledger.BookkeepingService book;

  public DocumentController(
      Store store,
      DocumentService documents,
      DocumentStorage storage,
      DocumentPublisher publisher,
      JobQueue jobs,
      HistoryService history,
      pt.conta.ledger.BookkeepingService book, RepairService repairs) {
    this.repairs = repairs;
    this.store = store;
    this.documents = documents;
    this.storage = storage;
    this.publisher = publisher;
    this.jobs = jobs;
    this.history = history;
    this.book = book;
  }

  /** Accepts one or many files and returns independently trackable document identities. */
  @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public List<Map<String, Object>> upload(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam String kind,
      @RequestParam(required = false) UUID accountId)
      throws IOException {
    var result = new ArrayList<Map<String, Object>>();
    for (var file : files)
      try (var input = file.getInputStream()) {
        result.add(
            documents.upload(
                input,
                Objects.requireNonNullElse(file.getOriginalFilename(), "documento"),
                kind,
                accountId,
                null,
                null));
      }
    return result;
  }

  /** Lists metadata, excluding large text/layout payloads until a document is opened. */
  @GetMapping("/documents")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> documents() {
    return documents.list();
  }

  /** Returns all fields, layout and raw draft for the review editor. */
  @GetMapping("/documents/{id}")
  @Transactional(readOnly = true)
  public Map<String, Object> document(@PathVariable UUID id) {
    return documents.view(store.get(SourceDocument.class, id));
  }

  /** Returns all business records produced by an original for audit and corpus evaluation. */
  @GetMapping("/documents/{id}/records")
  @Transactional(readOnly = true)
  public Map<String, Object> records(@PathVariable UUID id) {
    store.get(SourceDocument.class, id);
    return map(
        "invoices",
        store.all(pt.conta.invoice.Invoice.class).stream()
            .filter(i -> id.equals(i.documentId) && !i.deleted)
            .map(book::invoiceView)
            .toList(),
        "movements",
        store.all(pt.conta.bank.BankMovement.class).stream()
            .filter(m -> id.equals(m.documentId) && !m.deleted)
            .map(book::movementView)
            .toList());
  }

  /** Streams the immutable original through authenticated same-origin access. */
  @GetMapping("/documents/{id}/original")
  public ResponseEntity<Resource> original(@PathVariable UUID id) {
    var d = store.get(SourceDocument.class, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(d.mediaType))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(d.filename, java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(storage.path(d.storageKey)));
  }

  /** Renders one original PDF page for visual evidence, without modifying the original. */
  @GetMapping("/documents/{id}/pages/{page}")
  public ResponseEntity<byte[]> page(@PathVariable UUID id, @PathVariable int page)
      throws IOException {
    var d = store.get(SourceDocument.class, id);
    if (!d.mediaType.equals("application/pdf"))
      throw DomainException.invalid("O documento não é PDF");
    try (var pdf = Loader.loadPDF(storage.path(d.storageKey).toFile())) {
      if (page < 1 || page > pdf.getNumberOfPages()) throw DomainException.missing();
      var out = new ByteArrayOutputStream();
      ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(page - 1, 130), "png", out);
      return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(out.toByteArray());
    }
  }

  /** Saves a raw draft, or validates and publishes it when resolve is explicitly requested. */
  @PutMapping("/documents/{id}/draft")
  public Map<String, Object> draft(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return publisher.edit(id, body, documents);
  }

  /** Schedules fresh processing using a new revision; reviewed data is not silently overwritten. */
  @PostMapping("/documents/{id}/reprocess")
  @Transactional
  public void reprocess(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    var d = store.get(SourceDocument.class, id);
    store.checkVersion(d, version(body));
    d.status = "QUEUED";
    d.updatedAt = java.time.Instant.now();
    store.flush();
    jobs.enqueue("EXTRACT", id, d.version, "{}");
  }

  /** Returns original extraction attempts with engine and evidence provenance. */
  @GetMapping("/documents/{id}/attempts")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> attempts(@PathVariable UUID id) {
    return store.all(ExtractionAttempt.class).stream()
        .filter(a -> a.documentId.equals(id))
        .map(
            a ->
                map(
                    "id",
                    a.id,
                    "createdAt",
                    a.createdAt,
                    "engine",
                    a.engine,
                    "outcome",
                    a.outcome,
                    "result",
                    Json.value(a.result),
                    "evidence",
                    Json.value(a.evidence)))
        .toList();
  }

  /** Lists both open and resolved review issues. */
  @GetMapping("/alerts")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> alerts() {
    return repairs.list();
  }

  @GetMapping("/alerts/{id}")
  public Map<String,Object> alert(@PathVariable UUID id) { return repairs.view(id); }

  /** An accepted repair is asynchronous; repeated clicks reuse the current active request. */
  @PostMapping("/alerts/{id}/ai")
  public ResponseEntity<Map<String,Object>> repair(@PathVariable UUID id) {
    return ResponseEntity.accepted().body(repairs.request(id));
  }

  /** Applies a reviewed AI proposal only if its source draft has not changed in the meantime. */
  @PostMapping("/alerts/{id}/apply")
  @Transactional
  public Map<String, Object> apply(@PathVariable UUID id) {
    repairs.assertApplicable(id);
    var alert = store.get(Alert.class, id);
    if (alert.proposal == null || alert.documentId == null)
      throw DomainException.invalid("Não existe uma proposta aplicável");
    var proposal = Json.object(alert.proposal);
    if (!proposal.containsKey("draft") || !proposal.containsKey("documentVersion"))
      throw DomainException.invalid("A resposta contém sugestões, não uma correção de dados");
    return publisher.edit(
        alert.documentId,
        map(
            "version",
            proposal.get("documentVersion"),
            "draft",
            proposal.get("draft"),
            "resolve",
            true),
        documents);
  }

  /** Lists queue attempts and transient model outages without exposing request credentials. */
  @GetMapping("/jobs")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> jobs(@RequestParam(defaultValue = "500") int limit) {
    return store.all(Job.class).stream()
        .sorted(Comparator.comparing((Job j) -> j.createdAt).reversed())
        .limit(Math.clamp(limit, 1, 10000))
        .map(
            j ->
                map(
                    "id",
                    j.id,
                    "kind",
                    j.kind,
                    "targetId",
                    j.targetId,
                    "state",
                    j.state,
                    "attempts",
                    j.attempts,
                    "error",
                    j.error,
                    "availableAt",
                    j.availableAt))
        .toList();
  }

  /** Retries a failed or waiting task explicitly. */
  @PostMapping("/jobs/{id}/retry")
  public void retry(@PathVariable UUID id) {
    jobs.retry(id);
  }

  /** Returns imported Excel rows and the original cell/formula evidence. */
  @GetMapping("/history")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> history() {
    return store.all(LegacyRow.class).stream()
        .map(
            r ->
                map(
                    "id",
                    r.id,
                    "documentId",
                    r.documentId,
                    "sheet",
                    r.sheet,
                    "rowNumber",
                    r.rowNumber,
                    "year",
                    r.year,
                    "month",
                    r.month,
                    "date",
                    r.date,
                    "description",
                    r.description,
                    "received",
                    Money.text(r.received),
                    "payment",
                    Money.text(r.payment),
                    "balance",
                    Money.text(r.balance),
                    "cells",
                    Json.value(r.cells),
                    "invoiceId",
                    r.invoiceId,
                    "status",
                    r.status))
        .toList();
  }

  /** Associates a historical row with an existing owner-confirmed invoice. */
  @PostMapping("/history/{id}/link")
  public void link(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    history.link(id, uuid(body, "invoiceId"));
  }

  /** Returns ZIP import checkpoints separately from the children's extraction status. */
  @GetMapping("/imports")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> imports() {
    return store.all(ImportBatch.class).stream()
        .map(
            b ->
                map(
                    "id",
                    b.id,
                    "filename",
                    b.filename,
                    "status",
                    b.status,
                    "completedEntries",
                    b.completedEntries,
                    "message",
                    b.message))
        .toList();
  }

  /** Returns immutable user/system edit history for one resource. */
  @GetMapping("/audit/{type}/{id}")
  @Transactional(readOnly = true)
  public List<Map<String, Object>> audit(@PathVariable String type, @PathVariable UUID id) {
    return store.all(AuditEvent.class).stream()
        .filter(a -> a.entityType.equals(type) && a.entityId.equals(id))
        .map(
            a ->
                map(
                    "id",
                    a.id,
                    "createdAt",
                    a.createdAt,
                    "actor",
                    a.actor,
                    "action",
                    a.action,
                    "before",
                    a.beforeValue == null ? null : Json.value(a.beforeValue),
                    "after",
                    a.afterValue == null ? null : Json.value(a.afterValue)))
        .toList();
  }
}
