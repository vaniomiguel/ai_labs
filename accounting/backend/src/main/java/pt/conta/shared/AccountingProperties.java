package pt.conta.shared;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External settings; credentials deliberately have no production defaults. */
@ConfigurationProperties("accounting")
public record AccountingProperties(
    Path storage,
    String adminUsername,
    String adminPassword,
    String ollamaUrl,
    String documentWorkerUrl,
    String promptDirectory,
    int crossYearMonths,
    boolean jobsEnabled,
    int maxZipEntries,
    long maxExpandedBytes,
    long maxDocumentBytes) {}
