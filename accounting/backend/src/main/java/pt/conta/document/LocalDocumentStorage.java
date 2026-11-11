package pt.conta.document;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import pt.conta.shared.*;

/** Immutable, content-hashed originals in a persistent local volume. */
@Component
public class LocalDocumentStorage implements DocumentStorage {
  private final Path root;

  public LocalDocumentStorage(AccountingProperties properties) throws IOException {
    root = properties.storage().toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  /** Streams uploads to private files while hashing and enforcing a hard byte limit. */
  @Override
  public Stored put(InputStream input, long maximum) throws IOException {
    String key = UUID.randomUUID().toString();
    Path target = path(key);
    long size = 0;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) {
          size += read;
          if (size > maximum)
            throw DomainException.invalid("O ficheiro excede o tamanho permitido");
          digest.update(buffer, 0, read);
          out.write(buffer, 0, read);
        }
      }
      return new Stored(key, HexFormat.of().formatHex(digest.digest()), size);
    } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
      Files.deleteIfExists(target);
      if (e instanceof IOException io) throw io;
      if (e instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /** Validates that a key is an application-generated UUID contained in the storage root. */
  @Override
  public Path path(String key) {
    return root.resolve(UUID.fromString(key).toString());
  }

  /** Deletes an unreferenced duplicate upload, leaving its canonical original intact. */
  @Override
  public void discard(String key) throws IOException {
    Files.deleteIfExists(path(key));
  }
}
