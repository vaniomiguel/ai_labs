package pt.conta.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pt.conta.ai.*;
import pt.conta.document.ProcessingService;
import pt.conta.history.ZipImporter;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;

/** Runs one durable job at a time, keeping slow CPU/AI work off HTTP request threads. */
@Component
public class BackgroundWorker {
  private final java.util.concurrent.atomic.AtomicReference<JobQueue.Claim> active =
      new java.util.concurrent.atomic.AtomicReference<>();
  private static final Logger log = LoggerFactory.getLogger(BackgroundWorker.class);
  private final AccountingProperties properties;
  private final JobQueue queue;
  private final ProcessingService processing;
  private final ZipImporter zip;
  private final AiOrchestrator ai;
  private final ReviewService review;
  private final MeterRegistry metrics;

  public BackgroundWorker(
      AccountingProperties properties,
      JobQueue queue,
      ProcessingService processing,
      ZipImporter zip,
      AiOrchestrator ai,
      ReviewService review,
      MeterRegistry metrics) {
    this.properties = properties;
    this.queue = queue;
    this.processing = processing;
    this.zip = zip;
    this.ai = ai;
    this.review = review;
    this.metrics = metrics;
  }

  /**
   * Claims once per tick; transient model outages wait, while malformed results have bounded
   * retries.
   */
  @Scheduled(fixedDelay = 1000)
  public void tick() {
    if (!properties.jobsEnabled()) return;
    var claim = queue.claim();
    if (claim.isEmpty()) return;
    var job = claim.get();
    active.set(job);
    long started = System.nanoTime();
    try {
      switch (job.kind()) {
        case "EXTRACT" -> processing.extract(job);
        case "IMPORT_ZIP" -> zip.run(job.target());
        default -> ai.process(job);
      }
      queue.finish(job, null, false);
      review.resolveFor("job", job.id());
      metrics.counter("accounting.jobs.completed", "kind", job.kind()).increment();
    } catch (OllamaProvider.AiUnavailable e) {
      queue.finish(job, e.getMessage(), true);
      metrics.counter("accounting.ai.unavailable").increment();
    } catch (Exception e) {
      log.warn("Job {} ({}) failed: {}", job.id(), job.kind(), e.getMessage());
      queue.finish(job, e.getMessage(), false);
      metrics.counter("accounting.jobs.failed", "kind", job.kind()).increment();
      review.open(
          job.kind().equals("EXTRACT") || job.kind().equals("IMPORT_ZIP") ? job.target() : null,
          "job",
          job.id(),
          "PROCESSING_FAILED",
          "O processamento precisa de atenção: " + e.getMessage());
    } finally {
      active.set(null);
      metrics
          .timer("accounting.jobs.duration", "kind", job.kind())
          .record(java.time.Duration.ofNanos(System.nanoTime() - started));
    }
  }

  /** Keeps an in-flight task leased without preventing progress broadcasts. */
  @Scheduled(fixedDelay = 10000)
  public void heartbeat() {
    var job = active.get();
    if (job != null) queue.heartbeat(job);
  }
}
