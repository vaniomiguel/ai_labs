package pt.conta.ai;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.bank.BankStatement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Category;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.reconciliation.Allocation;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.review.*;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/**
 * Applies completed AI results in short transactions, checking target revision and human ownership.
 */
@Service
public class AiResults {
  private final Store store;
  private final ReviewService review;
  private final BookkeepingService book;
  private final JobQueue jobs;
  private final AuditService audit;

  public AiResults(
      Store store,
      ReviewService review,
      BookkeepingService book,
      JobQueue jobs,
      AuditService audit) {
    this.store = store;
    this.review = review;
    this.book = book;
    this.jobs = jobs;
    this.audit = audit;
  }

  /** Appends full execution provenance before any proposal can affect business data. */
  @Transactional
  public void record(AiExecution execution) {
    store.add(execution);
  }

  /** Normalizes only machine-assigned tags, preserving owner choices and every monetary field. */
  @Transactional
  public void categories(JobQueue.Claim job, List<String> names) {
    store.lockFinancialWrites();
    var invoice = store.get(Invoice.class, job.target());
    if (invoice.version != job.revision() || invoice.manuallyConfirmed || invoice.deleted) return;
    var before = new LinkedHashSet<>(invoice.categoryIds);
    var after = new LinkedHashSet<UUID>();
    for (String name : names) {
      if (name.isBlank() || name.length() > 120)
        throw DomainException.invalid("Categoria proposta inválida");
      var category =
          store.all(Category.class).stream()
              .filter(
                  c ->
                      !c.deleted
                          && MatchingRules.normalize(c.name).equals(MatchingRules.normalize(name)))
              .findFirst()
              .orElseGet(
                  () -> {
                    var c = new Category();
                    c.name = name;
                    return store.add(c);
                  });
      after.add(category.id);
    }
    if (before.equals(after)) return;
    invoice.categoryIds.clear();
    invoice.categoryIds.addAll(after);
    invoice.origin = "AI";
    invoice.author = "AI";
    audit.record("NORMALIZE_CATEGORIES", "invoice", invoice.id, before, after);
    store.flush();
    jobs.enqueue("REVIEW_INVOICE", invoice.id, invoice.version, "{}");
    if (invoice.documentId != null) {
      var doc = store.get(SourceDocument.class, invoice.documentId);
      if (doc.status.equals("DONE")) {
        var draft = Json.object(doc.draft);
        draft.put("categories", names);
        doc.draft = Json.write(draft);
      }
    }
  }

  /** Applies a grounded concern to automatic data, leaving manually confirmed facts untouched. */
  @Transactional
  public void reviewed(JobQueue.Claim job, Map<String, Object> result, String source) {
    store.lockFinancialWrites();
    if (!"CONCERN".equals(result.get("verdict"))) return;
    var concerns = Inputs.rows(result, "concerns");
    var grounded = concerns.stream().filter(c -> AiEvidence.quoteExists(c, source)).toList();
    if (grounded.isEmpty()) return;
    String type;
    UUID document = null;
    boolean manual;
    BaseEntity target;
    if (job.kind().equals("REVIEW_INVOICE")) {
      var i = store.get(Invoice.class, job.target());
      target = i;
      type = "invoice";
      document = i.documentId;
      manual = i.manuallyConfirmed;
    } else if (job.kind().equals("REVIEW_MOVEMENT")) {
      var m = store.get(BankMovement.class, job.target());
      target = m;
      type = "movement";
      document = m.documentId;
      manual = m.manuallyConfirmed;
    } else if (job.kind().equals("REVIEW_ALLOCATION")) {
      var a = store.get(Allocation.class, job.target());
      target = a;
      type = "allocation";
      document = store.get(Invoice.class, a.invoiceId).documentId;
      manual = "MANUAL".equals(a.origin);
    } else {
      var d = store.get(SourceDocument.class, job.target());
      target = d;
      type = "document";
      document = d.id;
      manual =
          store.all(Invoice.class).stream()
                  .anyMatch(i -> d.id.equals(i.documentId) && i.manuallyConfirmed)
              || store.all(BankMovement.class).stream()
                  .anyMatch(m -> d.id.equals(m.documentId) && m.manuallyConfirmed);
    }
    if (target.version != job.revision()) return;
    if (target instanceof BankMovement
        || target instanceof SourceDocument d && d.kind.equals("STATEMENT")) {
      grounded =
          grounded.stream()
              .filter(c -> AiEvidence.bankFieldRelevant(Objects.toString(c.get("field"), "")))
              .toList();
      if (grounded.isEmpty()) return;
      result = new LinkedHashMap<>(result);
      result.put("concerns", grounded);
    }
    var alert =
        review.open(
            document,
            type,
            target.id,
            "AI_CONCERN",
            Objects.toString(result.get("summary"), "Divergência identificada pela IA"));
    alert.proposal = Json.write(result);
    if (manual) return;
    if (target instanceof Invoice i) i.reviewStatus = ReviewStatus.OPEN;
    else if (target instanceof BankMovement m) m.reviewStatus = ReviewStatus.OPEN;
    else if (target instanceof Allocation a) {
      a.active = false;
      var setting = new AppSetting();
      setting.name = "rejected:" + a.invoiceId + ":" + a.movementId;
      var existing =
          store.all(AppSetting.class).stream().filter(s -> s.name.equals(setting.name)).findFirst();
      if (existing.isPresent()) existing.get().value = "true";
      else {
        setting.value = "true";
        store.add(setting);
      }
    } else if (target instanceof SourceDocument d) {
      store.all(BankMovement.class).stream()
          .filter(m -> d.id.equals(m.documentId))
          .forEach(m -> m.reviewStatus = ReviewStatus.OPEN);
      store.all(Invoice.class).stream()
          .filter(i -> d.id.equals(i.documentId))
          .forEach(i -> i.reviewStatus = ReviewStatus.OPEN);
      store.all(BankStatement.class).stream()
          .filter(s -> d.id.equals(s.documentId))
          .forEach(s -> s.validated = false);
      d.status = "OPEN";
    }
    if (document != null && !(target instanceof Allocation))
      store.get(SourceDocument.class, document).status = "OPEN";
    book.synchronize();
  }

  /** Stores a repair proposal only; an explicit owner action is still required to apply it. */
  @Transactional
  public void repair(
      UUID alertId, long version, Map<String, Object> proposal, long documentVersion) {
    var alert = store.get(Alert.class, alertId);
    if (alert.version != version || !alert.status.equals("OPEN")) return;
    var value = new LinkedHashMap<>(proposal);
    value.put("documentVersion", documentVersion);
    alert.proposal = Json.write(value);
  }
}
