package pt.conta.history;

import static pt.conta.document.SourceDocument.INVOICE_ARCHIVE;

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
    // An account-free archive has its own manifest under the existing hash/account key.
    boolean invoiceOnly = INVOICE_ARCHIVE.equals(document.kind);
    boolean foundInvoicePdf = false;
    long expanded = 0;
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
        long declared = Math.max(0, entry.getSize());
        long expandedBeforeEntry = expanded;
        expanded = expandedWithEntry(expanded, declared);
        if (entry.isDirectory()) continue;
        String kind = entryKind(path, invoiceOnly);
        foundInvoicePdf |= "INVOICE".equals(kind);
        if (index <= batch.completedEntries) continue;
        if (kind != null) {
          long remainingBytes = properties.maxExpandedBytes() - expandedBeforeEntry;
          expanded = expandedBeforeEntry + uploadEntry(
              zip, entry, path, kind, document.accountId, batch.id, remainingBytes);
        }
        state.checkpoint(batch.id, index);
      }
    }
    if (invoiceOnly && !foundInvoicePdf)
      throw DomainException.invalid("O ZIP não contém PDFs de faturas para importar.");
    state.complete(batch.id, id);
  }

  private long expandedWithEntry(long expanded, long declared) {
    if (declared > properties.maxDocumentBytes())
      throw DomainException.invalid("Uma entrada excede o limite de documento");
    if (declared > properties.maxExpandedBytes() - expanded)
      throw DomainException.invalid("O ZIP descomprimido excede o limite");
    return expanded + declared;
  }

  private static String entryKind(String path, boolean invoiceOnly) {
    String lower = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
    if (lower.startsWith(".") || path.contains("__MACOSX/") || !lower.matches(".*\\.(pdf|xlsx|xls)$"))
      return null;
    if (lower.endsWith(".pdf")) {
      boolean statement = lower.contains("extrato") || lower.contains("extracto") || lower.contains("statement");
      if (!statement) return "INVOICE";
      return invoiceOnly ? null : "STATEMENT";
    }
    return invoiceOnly ? null : "HISTORY";
  }

  private long uploadEntry(ZipFile zip, ZipEntry entry, String path, String kind, UUID archiveAccount,
      UUID batchId, long remainingBytes) throws IOException {
    String filename = Path.of(path).getFileName().toString();
    UUID account = kind.equals("STATEMENT") ? archiveAccount : null;
    try (var input = new FilterInputStream(zip.getInputStream(entry)) {
      long bytesRead;

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = in.read(buffer, offset, length);
        if (count > 0) {
          bytesRead += count;
          if (bytesRead > remainingBytes)
            throw new IOException("Limite de descompressão excedido");
        }
        return count;
      }
    }) {
      documents.upload(input, filename, kind, account, batchId, path);
      return input.bytesRead;
    }
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
