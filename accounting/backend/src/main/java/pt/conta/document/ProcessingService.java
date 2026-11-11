package pt.conta.document;

import java.util.*;
import org.springframework.stereotype.Service;
import pt.conta.history.ExcelReader;
import pt.conta.jobs.JobQueue;
import pt.conta.shared.*;

/** Performs expensive conversion outside transactions and publishes version-checked results. */
@Service
public class ProcessingService {
  private final Store store;
  private final DocumentStorage storage;
  private final DocumentConverter converter;
  private final PortugueseDocumentParser parser;
  private final DocumentPublisher publisher;
  private final ExcelReader excel;
  private final Settings settings;
  private final JobQueue jobs;

  public ProcessingService(
      Store store,
      DocumentStorage storage,
      DocumentConverter converter,
      PortugueseDocumentParser parser,
      DocumentPublisher publisher,
      ExcelReader excel,
      Settings settings,
      JobQueue jobs) {
    this.store = store;
    this.storage = storage;
    this.converter = converter;
    this.parser = parser;
    this.publisher = publisher;
    this.excel = excel;
    this.settings = settings;
    this.jobs = jobs;
  }

  /** Parses one original, preserving all intermediate text, layout and editable draft fields. */
  public void extract(JobQueue.Claim job) throws Exception {
    var doc = store.get(SourceDocument.class, job.target());
    if (doc.version != job.revision() || doc.deleted) return;
    DocumentConverter.Converted converted;
    Map<String, Object> draft;
    if (doc.mediaType.equals("application/pdf")) {
      converted = converter.convert(storage.path(doc.storageKey));
      draft =
          doc.kind.equals("STATEMENT")
              ? parser.statement(converted.text())
              : parser.invoice(converted.text(), doc.filename);
      if (converted.layout().containsKey("conversionError")) {
        var issues = new ArrayList<Object>((List<?>) draft.getOrDefault("issues", List.of()));
        issues.add(converted.layout().get("conversionError"));
        draft.put("issues", issues);
        draft.put("validate", false);
      }
    } else {
      draft =
          excel.read(
              storage.path(doc.storageKey),
              doc.kind,
              Objects.requireNonNullElse(doc.sourcePath, doc.filename),
              Json.object(settings.get("excelMapping:" + doc.kind, "{}")));
      converted =
          new DocumentConverter.Converted(
              Json.write(draft.get("sheets")),
              0,
              Inputs.map("sheets", draft.get("sheets"), "engine", "APACHE_POI"),
              "APACHE_POI");
    }
    if (publisher.extracted(doc.id, job.revision(), converted, draft)) {
      var current = store.get(SourceDocument.class, doc.id);
      if (Set.of("INVOICE", "STATEMENT", "HISTORY").contains(doc.kind)) {
        jobs.enqueue(
            current.status.equals("OPEN") && !doc.kind.equals("HISTORY")
                ? "AI_EXTRACT_DOCUMENT"
                : "REVIEW_DOCUMENT",
            doc.id,
            current.version,
            "{}");
      }
    }
  }
}
