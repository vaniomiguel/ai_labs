package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.ai.AutomaticReviewContext.*;
import pt.conta.ai.AutomaticReviewEvidence.Finding;
import pt.conta.bank.BankMovement;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.JobQueue;
import pt.conta.ledger.BookkeepingService;
import pt.conta.reconciliation.Allocation;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/** Applies a verified review report once, using current revisions and the queue lease. */
@Service
class AutomaticReviewResults {
  private final Store store;
  private final ReviewService review;
  private final BookkeepingService book;
  private final JobQueue jobs;
  private final AutomaticReviewEvidence evidence;

  AutomaticReviewResults(
      Store store, ReviewService review, BookkeepingService book, JobQueue jobs, AutomaticReviewEvidence evidence) {
    this.store = store;
    this.review = review;
    this.book = book;
    this.jobs = jobs;
    this.evidence = evidence;
  }

  @Transactional
  public void complete(
      JobQueue.Claim job, Snapshot snapshot, AutomaticReviewWorker.Report report, String model) {
    store.lockFinancialWrites();
    if (!current(snapshot.target()) || snapshot.revisions().stream().anyMatch(revision -> !current(revision)))
      return;

    var result = report.view();
    result.put("model", model);
    result.put("reviewedRevision", snapshot.target().version());
    result.put("reviewedSourcesAndRecords", snapshot.revisions());
    if (!jobs.saveAutomaticReview(job, result)) return;
    if (report.findings().isEmpty()) return;

    String type = snapshot.kind().name().toLowerCase(Locale.ROOT);
    String code = report.verdict().equals("CONCERN") ? "AI_CONCERN" : "AI_REVIEW_SUGGESTION";
    var alert = review.open(snapshot.alertDocument(), type, snapshot.target().id(), code, report.summary());
    alert.proposal = Json.write(result);
    reopenAffectedRecords(report.findings());
  }

  private void reopenAffectedRecords(List<Finding> findings) {
    var reopened = new HashSet<UUID>();
    for (var finding : findings) {
      if (!finding.verified() || finding.field().protectedValue()) continue;
      var record = finding.field().record();
      UUID id = record.revision().id();
      if (!record.automatic() || record.excluded() || reopened.contains(id) || hasManualAllocation(id)) continue;
      if (reopen(record)) reopened.add(id);
    }
    if (!reopened.isEmpty()) {
      store.flush();
      book.synchronize();
    }
  }

  private boolean reopen(SavedRecord record) {
    var revision = record.revision();
    if (revision.kind() == Kind.INVOICE) {
      var invoice = store.get(Invoice.class, revision.id());
      if (!uniqueInvoice(invoice)) return false;
      invoice.reviewStatus = ReviewStatus.OPEN;
      return true;
    }
    if (revision.kind() == Kind.MOVEMENT) {
      var movement = store.get(BankMovement.class, revision.id());
      if (!uniqueMovement(movement, record.identity())) return false;
      movement.reviewStatus = ReviewStatus.OPEN;
      return true;
    }
    return false;
  }

  private boolean uniqueInvoice(Invoice invoice) {
    return store.all(Invoice.class).stream()
        .filter(other -> !other.deleted && Objects.equals(other.documentId, invoice.documentId))
        .filter(other -> invoice.number.equalsIgnoreCase(other.number)).count() == 1;
  }

  private boolean uniqueMovement(BankMovement movement, Map<String, Object> identity) {
    return store.all(BankMovement.class).stream()
        .filter(other -> !other.deleted && Objects.equals(other.documentId, movement.documentId))
        .filter(other -> evidence.sameMovement(
            map("bookedOn", other.bookedOn, "description", other.description), identity))
        .count() == 1;
  }

  private boolean hasManualAllocation(UUID record) {
    // Synchronizing an OPEN record can otherwise withdraw a human-confirmed payment association.
    var active = store.all(Allocation.class).stream().filter(a -> a.active).toList();
    var affected = new HashSet<UUID>();
    active.stream().filter(a -> record.equals(a.invoiceId) || record.equals(a.movementId))
        .forEach(a -> affected.add(a.id));
    return active.stream().anyMatch(a -> "MANUAL".equals(a.origin)
        && (affected.contains(a.id) || affected.contains(a.reversesId)));
  }

  private boolean current(Revision revision) {
    try {
      return switch (revision.kind()) {
        case INVOICE -> {
          var row = store.get(Invoice.class, revision.id());
          yield !row.deleted && row.version == revision.version();
        }
        case MOVEMENT -> {
          var row = store.get(BankMovement.class, revision.id());
          yield !row.deleted && row.version == revision.version();
        }
        case DOCUMENT -> {
          var row = store.get(SourceDocument.class, revision.id());
          yield row.version == revision.version();
        }
        case ALLOCATION -> {
          var row = store.get(Allocation.class, revision.id());
          yield row.active && row.version == revision.version();
        }
      };
    } catch (DomainException e) {
      if (e.status != 404) throw e;
      return false;
    }
  }
}
