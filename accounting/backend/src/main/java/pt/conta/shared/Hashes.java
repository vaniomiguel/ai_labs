package pt.conta.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Stable content fingerprints used for idempotent jobs and stale-proposal detection. */
public final class Hashes {
  private Hashes() {}

  /** Hashes UTF-8 content without logging document contents. */
  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
