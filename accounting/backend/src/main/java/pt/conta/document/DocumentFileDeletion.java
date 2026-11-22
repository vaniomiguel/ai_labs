package pt.conta.document;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists file removal with the database change and retries it after commit. */
@Service
public class DocumentFileDeletion {
  private static final Logger log = LoggerFactory.getLogger(DocumentFileDeletion.class);
  private final JdbcTemplate jdbc;
  private final DocumentStorage storage;
  private final TransactionTemplate transactions;

  public DocumentFileDeletion(JdbcTemplate jdbc, DocumentStorage storage, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.transactions = transactions;
  }

  void enqueue(String key) {
    if (key != null) jdbc.update("insert into document_file_deletion(storage_key) values (?) on conflict do nothing", key);
  }

  @Scheduled(fixedDelay = 5000)
  public void drain() {
    // Each key commits independently so an unavailable file cannot hold up the rest.
    for (int i = 0; i < 20; i++) {
      if (!Boolean.TRUE.equals(transactions.execute(_ -> discardNext()))) return;
    }
  }

  private boolean discardNext() {
    var keys = jdbc.queryForList("""
        select storage_key from document_file_deletion where available_at <= now()
        order by available_at, storage_key limit 1 for update skip locked
        """, String.class);
    if (keys.isEmpty()) return false;
    String key = keys.getFirst();
    try {
      storage.discard(key);
      jdbc.update("delete from document_file_deletion where storage_key=?", key);
    } catch (IOException | IllegalArgumentException e) {
      jdbc.update("""
          update document_file_deletion set attempts=attempts+1,
            available_at=now()+interval '1 minute' where storage_key=?
          """, key);
      log.warn("Could not remove an archived document file. Will retry key {}", key, e);
    }
    return true;
  }
}
