package pt.conta.api;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sends small same-origin progress snapshots while background work continues. */
@RestController
public class ProgressController {
  private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
  private final JdbcTemplate jdbc;

  public ProgressController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Opens an authenticated event stream with cleanup on completion or disconnect. */
  @GetMapping(value = "/api/v1/events", produces = "text/event-stream")
  public SseEmitter events() {
    var client = new SseEmitter(0L);
    clients.add(client);
    client.onCompletion(() -> clients.remove(client));
    client.onTimeout(() -> clients.remove(client));
    client.onError(e -> clients.remove(client));
    return client;
  }

  /** Broadcasts counts, avoiding document text or other sensitive payloads in progress events. */
  @Scheduled(fixedDelay = 3000)
  public void broadcast() {
    if (clients.isEmpty()) return;
    var state =
        Map.of(
            "jobs",
            jdbc.queryForObject(
                "select count(*) from job where state in ('QUEUED','WAITING','RUNNING')",
                Long.class),
            "alerts",
            jdbc.queryForObject("select count(*) from alert where status='OPEN'", Long.class),
            "revision",
            jdbc.queryForObject(
                "select greatest(coalesce((select max(updated_at) from"
                    + " job),'epoch'::timestamptz),coalesce((select max(updated_at) from"
                    + " alert),'epoch'::timestamptz))::text",
                String.class));
    for (var client : clients)
      try {
        client.send(SseEmitter.event().name("progress").data(state));
      } catch (Exception e) {
        clients.remove(client);
        client.complete();
      }
  }
}
