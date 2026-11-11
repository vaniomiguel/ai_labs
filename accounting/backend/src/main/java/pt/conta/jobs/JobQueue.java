package pt.conta.jobs;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-backed queue with atomic claims, leases and idempotent enqueue operations. */
@Service
public class JobQueue {
  private final JdbcTemplate jdbc;
  private final boolean aiEnabled;

  public JobQueue(
      JdbcTemplate jdbc,
      @org.springframework.beans.factory.annotation.Value("${accounting.ai-enabled:true}")
          boolean aiEnabled) {
    this.jdbc = jdbc;
    this.aiEnabled = aiEnabled;
  }

  /** Enqueues work in the caller's transaction, once per target revision. */
  public void enqueue(String kind, UUID target, long version, String payload) {
    enqueue(kind, target, version, payload, "");
  }

  /** Distinguishes matching suggestions when bank candidates change without editing the invoice. */
  public void enqueue(String kind, UUID target, long version, String payload, String fingerprint) {
    jdbc.update(
        "insert into"
            + " job(id,version,created_at,updated_at,kind,target_id,target_version,dedupe_key,state,attempts,available_at,payload)"
            + " values (?,0,now(),now(),?,?,?,?, 'QUEUED',0,now(),?) on conflict(dedupe_key) do"
            + " nothing",
        UUID.randomUUID(),
        kind,
        target,
        version,
        kind + ":" + target + ":" + version + ":" + fingerprint,
        payload == null ? "{}" : payload);
  }

  /** Claims one due job without holding locks while document or model calls execute. */
  @Transactional
  public Optional<Claim> claim() {
    var rows =
        jdbc.query(
            """
            update job set state='RUNNING', lease_token=?, leased_until=now()+interval '30 minutes', updated_at=now()
            where id=(select id from job where ((state in ('QUEUED','WAITING') and available_at<=now())
              or (state='RUNNING' and leased_until<now())) and (? or kind in ('EXTRACT','IMPORT_ZIP')) order by created_at for update skip locked limit 1)
            returning id,kind,target_id,target_version,payload,attempts,lease_token
            """,
            (rs, n) ->
                new Claim(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getLong(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getObject(7, UUID.class)),
            UUID.randomUUID(),
            aiEnabled);
    return rows.stream().findFirst();
  }

  /** Completes or defers work; offline AI remains retryable without consuming failure attempts. */
  public void finish(Claim job, String error, boolean unavailable) {
    if (error == null)
      jdbc.update(
          "update job set state='DONE',leased_until=null,error=null,updated_at=now() where id=? and"
              + " lease_token=?",
          job.id(),
          job.token());
    else
      jdbc.update(
          "update job set state=?,attempts=?,available_at=now()+interval '2"
              + " minutes',leased_until=null,error=?,updated_at=now() where id=? and lease_token=?",
          unavailable ? "WAITING" : job.attempts() + 1 >= 5 ? "FAILED" : "QUEUED",
          unavailable ? job.attempts() : job.attempts() + 1,
          error,
          job.id(),
          job.token());
  }

  /** Renews only the current owner's lease while long OCR or multi-page AI work is in progress. */
  public void heartbeat(Claim job) {
    jdbc.update(
        "update job set leased_until=now()+interval '30 minutes' where id=? and lease_token=? and"
            + " state='RUNNING'",
        job.id(),
        job.token());
  }

  /** Restarts a failed task explicitly, retaining its audit history in the database. */
  public void retry(UUID id) {
    jdbc.update(
        "update job set state='QUEUED',attempts=0,available_at=now(),error=null,updated_at=now()"
            + " where id=? and state in ('FAILED','WAITING')",
        id);
  }

  public record Claim(
      UUID id, String kind, UUID target, long revision, String payload, int attempts, UUID token) {
    public Claim(UUID id, String kind, UUID target, long revision, String payload, int attempts) {
      this(id, kind, target, revision, payload, attempts, null);
    }
  }
}
