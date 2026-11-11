package pt.conta;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.*;
import org.springframework.boot.SpringApplication;

/**
 * Optional local launcher with real PostgreSQL 18, avoiding a Docker requirement for development.
 */
public final class LocalDevelopment {
  /** Starts persistent local data and closes the application before its PostgreSQL process. */
  public static void main(String[] args) throws Exception {
    Path data = Path.of(System.getenv().getOrDefault("ACCOUNTING_DATA", "data")).toAbsolutePath();
    Files.createDirectories(data);
    var postgres =
        EmbeddedPostgres.builder()
            .setDataDirectory(data.resolve("postgres").toFile())
            .setCleanDataDirectory(false)
            .setPort(0)
            .start();
    var application = new SpringApplication(AccountingApplication.class);
    application.setRegisterShutdownHook(false);
    var launch =
        java.util.stream.Stream.concat(
                java.util.stream.Stream.of(
                    "--spring.profiles.active=dev",
                    "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres",
                    "--spring.datasource.password=postgres",
                    "--accounting.storage=" + data.resolve("documents")),
                java.util.Arrays.stream(args))
            .toArray(String[]::new);
    try {
      var context = application.run(launch);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    context.close();
                    try {
                      postgres.close();
                    } catch (Exception ignored) {
                    }
                  }));
    } catch (Exception e) {
      postgres.close();
      throw e;
    }
  }
}
