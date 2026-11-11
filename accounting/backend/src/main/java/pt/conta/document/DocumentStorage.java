package pt.conta.document;

import java.io.*;
import java.nio.file.Path;

/** Storage boundary; domain records retain opaque keys rather than filesystem paths. */
public interface DocumentStorage {
  record Stored(String key, String sha256, long size) {}

  /** Archives a bounded stream without modifying existing originals. */
  Stored put(InputStream input, long maximum) throws IOException;

  /** Resolves an opaque key controlled by the application, never a caller-supplied path. */
  Path path(String key);

  /** Removes only an unreferenced temporary duplicate created by an upload. */
  void discard(String key) throws IOException;
}
