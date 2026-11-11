package pt.conta.history;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.document.SourceDocument;
import pt.conta.shared.*;

/** Short transactional checkpoints allow a ZIP import to resume after a process restart. */
@Service
public class ZipImportState {
  private final Store store;

  public ZipImportState(Store store) {
    this.store = store;
  }

  /** Gets or creates the content-addressed archive manifest. */
  @Transactional
  public ImportBatch begin(UUID documentId) {
    store.lockFinancialWrites();
    var document = store.get(SourceDocument.class, documentId);
    var batch =
        store.all(ImportBatch.class).stream()
            .filter(
                b ->
                    b.sha256.equals(document.sha256)
                        && Objects.equals(b.accountId, document.accountId))
            .findFirst()
            .orElseGet(
                () -> {
                  var b = new ImportBatch();
                  b.filename = document.filename;
                  b.sha256 = document.sha256;
                  b.accountId = document.accountId;
                  return store.add(b);
                });
    document.batchId = batch.id;
    return batch;
  }

  /** Records one completed entry only after its child upload transaction committed. */
  @Transactional
  public void checkpoint(UUID id, long entries) {
    store.get(ImportBatch.class, id).completedEntries = entries;
  }

  /** Marks the archive complete; each child retains independent extraction/review progress. */
  @Transactional
  public void complete(UUID id, UUID documentId) {
    store.get(ImportBatch.class, id).status = "DONE";
    store.get(SourceDocument.class, documentId).status = "DONE";
  }
}
