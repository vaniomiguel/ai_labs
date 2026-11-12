package pt.conta.api;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pt.conta.jobs.JobMonitor;

/** Sends small same-origin progress snapshots while background work continues. */
@RestController
public class ProgressController {
  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private final JdbcTemplate jdbc;
  private final JobMonitor monitor;

  public ProgressController(JdbcTemplate jdbc, JobMonitor monitor) {
    this.jdbc = jdbc;
    this.monitor = monitor;
  }

  /** Opens an authenticated event stream with cleanup on completion or disconnect. */
  @GetMapping(value = "/api/v1/events", produces = "text/event-stream")
  public SseEmitter events() {
    var client = new SseEmitter(0L);
    clients.add(client);
    client.onCompletion(() -> clients.remove(client));
    client.onTimeout(() -> clients.remove(client));
    client.onError(e -> clients.remove(client));
    send(client, snapshot());
    return client;
  }

  /** Broadcasts counts, avoiding document text or other sensitive payloads in progress events. */
  @Scheduled(fixedDelay = 3000)
  public void broadcast() {
    if (clients.isEmpty()) return;
    var state = snapshot();
    for (var client : clients) send(client, state);
  }

  /** Ends persistent streams before the web server begins its graceful shutdown. */
  @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
  public void closeStreams() {
    for (var client : clients) client.complete();
    clients.clear();
  }

  public Map<String, Object> snapshot() {
    var counts = monitor.counts();
    return Map.of(
            "jobs",
            counts.get("active"),
            "jobStates", counts,
            "alerts",
            jdbc.queryForObject("select count(*) from alert where status='OPEN'", Long.class),
            "revision",
            jdbc.queryForObject(
                "select greatest(coalesce((select max(updated_at) from"
                    + " job),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " alert),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " invoice),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " bank_movement),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " allocation),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " ledger_entry),'epoch'::timestamptz))::text",
                String.class));
  }

  private void send(SseEmitter client, Map<String, Object> state) {
    try {
      client.send(SseEmitter.event().name("progress").data(state));
    } catch (Exception e) {
      clients.remove(client);
      client.complete();
    }
  }
}
