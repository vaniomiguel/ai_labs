package pt.conta.history;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import pt.conta.document.*;
import pt.conta.shared.*;

/** Streams archive entries through ordinary uploads; archive paths are never written to disk. */
@Service
public class ZipImporter {
  private final Store store;
  private final DocumentStorage storage;
  private final DocumentService documents;
  private final ZipImportState state;
  private final AccountingProperties properties;

  public ZipImporter(
      Store store,
      DocumentStorage storage,
      DocumentService documents,
      ZipImportState state,
      AccountingProperties properties) {
    this.store = store;
    this.storage = storage;
    this.documents = documents;
    this.state = state;
    this.properties = properties;
  }

  /**
   * Rejects traversal, absolute paths and decompression bombs while retaining durable checkpoints.
   */
  public void run(UUID id) throws Exception {
    var document = store.get(SourceDocument.class, id);
    var batch = state.begin(id);
    long[] expanded = {0};
    long index = 0;
    try (var zip = new ZipFile(storage.path(document.storageKey).toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        index++;
        if (index > properties.maxZipEntries())
          throw DomainException.invalid("O ZIP excede o número de entradas permitido");
        String path = entry.getName().replace('\\', '/');
        validatePath(path);
        if (entry.getSize() > properties.maxDocumentBytes())
          throw DomainException.invalid("Uma entrada excede o limite de documento");
        if (entry.getSize() > 0) {
          expanded[0] += entry.getSize();
          if (expanded[0] > properties.maxExpandedBytes())
            throw DomainException.invalid("O ZIP descomprimido excede o limite");
        }
        if (index <= batch.completedEntries || entry.isDirectory()) continue;
        String filename = Path.of(path).getFileName().toString();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")
            || path.contains("__MACOSX/")
            || !lower.matches(".*\\.(pdf|xlsx|xls)$")) {
          state.checkpoint(batch.id, index);
          continue;
        }
        String kind =
            lower.endsWith(".pdf")
                ? lower.contains("extrato")
                        || lower.contains("extracto")
                        || lower.contains("statement")
                    ? "STATEMENT"
                    : "INVOICE"
                : "HISTORY";
        long before = expanded[0];
        long declared = Math.max(0, entry.getSize());
        long[] actual = {0};
        try (var input =
            new FilterInputStream(zip.getInputStream(entry)) {
              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                if (n > 0) {
                  actual[0] += n;
                  if (before - declared + actual[0] > properties.maxExpandedBytes())
                    throw new IOException("Limite de descompressão excedido");
                }
                return n;
              }
            }) {
          documents.upload(
              input,
              filename,
              kind,
              kind.equals("STATEMENT") ? document.accountId : null,
              batch.id,
              path);
        }
        expanded[0] = before - declared + actual[0];
        state.checkpoint(batch.id, index);
      }
    }
    state.complete(batch.id, id);
  }

  /** Validates virtual archive paths even though entries are stored with opaque UUID keys. */
  public static void validatePath(String path) {
    if (path.startsWith("/")
        || path.matches("^[A-Za-z]:.*")
        || path.indexOf('\0') >= 0
        || Arrays.asList(path.split("/")).contains(".."))
      throw DomainException.invalid("O ZIP contém um caminho inseguro");
  }
}
