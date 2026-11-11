package pt.conta.document;

import java.nio.file.Path;
import java.util.*;

/** Converts physical documents to text/layout without making financial decisions. */
public interface DocumentConverter {
  record Converted(String text, int pages, Map<String, Object> layout, String engine) {}

  /** Converts a PDF using a native first pass and OCR when its text layer is insufficient. */
  Converted convert(Path path) throws Exception;
}
