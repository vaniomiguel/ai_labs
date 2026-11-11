package pt.conta.ai;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pt.conta.shared.*;

/** Lightweight per-record review provenance, excluding large prompts and model responses. */
@Service
public class AiReviewStatus {
  private final JdbcTemplate jdbc;
  private final Store store;

  public AiReviewStatus(JdbcTemplate jdbc, Store store) {
    this.jdbc = jdbc;
    this.store = store;
  }

  private record Header(long revision, String model, Instant at, String verdict) {}

  private record Pending(long revision, String state) {}

  private record Snapshot(Map<UUID, Header> reviews, Map<UUID, Pending> jobs) {}

  /** Distinguishes a current AI review from queued work or a review of an older data revision. */
  public Map<String, Object> describe(UUID target, long revision) {
    var snapshot = store.memo(Snapshot.class, this::load);
    var review = snapshot.reviews().get(target);
    var job = snapshot.jobs().get(target);
    String state =
        job != null
                && job.revision() == revision
                && Set.of("QUEUED", "RUNNING", "WAITING").contains(job.state())
            ? "PENDING"
            : review != null && review.revision() == revision
                ? review.verdict().equals("INVALID") ? "FAILED" : "REVIEWED"
                : job != null && job.revision() == revision && job.state().equals("FAILED")
                    ? "FAILED"
                    : review == null ? "NONE" : "PREVIOUS";
    return Inputs.map(
        "state",
        state,
        "jobState",
        job == null ? null : job.state(),
        "model",
        review == null ? null : review.model(),
        "at",
        review == null ? null : review.at(),
        "revision",
        review == null ? null : review.revision(),
        "verdict",
        review == null ? null : review.verdict());
  }

  private Snapshot load() {
    var reviews = new HashMap<UUID, Header>();
    var jobs = new HashMap<UUID, Pending>();
    jdbc.query(
        "select target_id,target_version,model,created_at,case when outcome='VALID' then"
            + " response::jsonb->>'verdict' else 'INVALID' end as verdict from ai_execution where"
            + " task='review' order by created_at desc",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              UUID id = rs.getObject(1, UUID.class);
              reviews.putIfAbsent(
                  id,
                  new Header(
                      rs.getLong(2),
                      rs.getString(3),
                      rs.getTimestamp(4).toInstant(),
                      Objects.requireNonNullElse(rs.getString(5), "INVALID")));
            });
    jdbc.query(
        "select target_id,target_version,state from job where kind in"
            + " ('REVIEW_INVOICE','REVIEW_MOVEMENT','REVIEW_ALLOCATION') order by created_at desc",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                jobs.putIfAbsent(
                    rs.getObject(1, UUID.class), new Pending(rs.getLong(2), rs.getString(3))));
    return new Snapshot(reviews, jobs);
  }
}
