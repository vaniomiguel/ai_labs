package pt.conta.jobs;

import static pt.conta.shared.Inputs.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.ai.AiProvider;
import pt.conta.shared.DomainException;

/** Small queue projections, independent of financial date filters and archived document contents. */
@Service
public class JobMonitor {
  private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "WAITING", "DONE", "FAILED");
  private static final String WARNING_JOIN = """
      left join lateral (
        select e.model as warning_model, e.created_at as warning_at, e.prompt_tokens,
          e.response_tokens, e.context_tokens, e.capacity_source, e.done_reason, e.context_warning
        from ai_execution e
        where e.job_id=j.id and e.context_warning is not null
        order by (e.context_warning='NEAR_CONTEXT_LIMIT'), e.created_at desc, e.id desc
        limit 1
      ) warning on true
      """;
  private static final String RECENT_WARNINGS = """
      select j.id, j.kind, warning.*
      from (
        select job_id, max(created_at) as latest_warning
        from ai_execution
        where job_id is not null and context_warning is not null
        group by job_id
        order by latest_warning desc, job_id desc
        limit 5
      ) recent
      join job j on j.id=recent.job_id
      """ + WARNING_JOIN + "order by recent.latest_warning desc, j.id desc";
  private static final String PROJECTION = """
      select j.*, i.id as invoice_id, i.number as invoice_number, i.supplier,
        i.deleted as invoice_deleted, m.id as movement_id, m.description,
        m.deleted as movement_deleted, d.id as document_id, d.filename, d.deleted as document_deleted,
        al.id as alert_id, al.message as alert_message, a.id as allocation_id,
        a.invoice_id as allocation_invoice_id, a.movement_id as allocation_movement_id,
        a.amount as allocation_amount, a.active as allocation_active, a.evidence as allocation_evidence,
        ai.deleted as allocation_invoice_deleted, am.deleted as allocation_movement_deleted,
        original.id as original_id, warning.*
      from (%s) j
      left join invoice i on i.id=j.target_id
        and j.kind in ('REVIEW_INVOICE','NORMALIZE_CATEGORIES','CLASSIFY_INVOICE','SUGGEST_RECONCILIATION')
      left join bank_movement m on m.id=j.target_id and j.kind in ('REVIEW_MOVEMENT','CLASSIFY_MOVEMENT')
      left join source_document d on d.id=j.target_id
        and j.kind in ('EXTRACT','IMPORT_ZIP','REVIEW_DOCUMENT','AI_EXTRACT_DOCUMENT')
      left join alert al on al.id=j.target_id and j.kind='REPAIR_ALERT'
      left join allocation a on a.id=j.target_id and j.kind='REVIEW_ALLOCATION'
      left join invoice ai on ai.id=a.invoice_id
      left join bank_movement am on am.id=a.movement_id
      left join source_document original on original.id=coalesce(i.document_id,m.document_id,d.id,al.document_id)
        and not original.deleted
      """ + WARNING_JOIN + "order by j.created_at desc,j.id desc";
  private final JdbcTemplate jdbc;

  public JobMonitor(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Counts the entire queue, including zeroes for states not currently represented. */
  public Map<String, Long> counts() {
    var result = new LinkedHashMap<String, Long>();
    for (String state : List.of("QUEUED", "RUNNING", "WAITING", "DONE", "FAILED")) result.put(state, 0L);
    jdbc.query("select state,count(*) from job group by state", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      if (STATES.contains(rs.getString(1))) result.put(rs.getString(1), rs.getLong(2));
    });
    long total = result.values().stream().mapToLong(Long::longValue).sum();
    result.put("active", result.get("QUEUED") + result.get("RUNNING") + result.get("WAITING"));
    result.put("total", total);
    return result;
  }

  /** Pages before joining related records, so history cannot hide pending tasks behind a limit. */
  @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> page(String state, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw DomainException.invalid("Paginação inválida");
    String where;
    Object[] arguments;
    if (state.equals("active")) {
      where = "where state in ('QUEUED','RUNNING','WAITING')";
      arguments = new Object[] {size, (long) page * size};
    } else if (state.equals("all")) {
      where = "";
      arguments = new Object[] {size, (long) page * size};
    } else if (STATES.contains(state)) {
      where = "where state=?";
      arguments = new Object[] {state, size, (long) page * size};
    } else throw DomainException.invalid("Estado de tarefa inválido");
    var counts = counts();
    var items = jdbc.query(
        PROJECTION.formatted("select * from job " + where + " order by created_at desc,id desc limit ? offset ?"),
        (rs, n) -> view(rs), arguments);
    return map("items", items, "total", counts.get(state.equals("all") ? "total" : state),
        "page", page, "size", size, "counts", counts, "recentContextWarnings", recentWarnings());
  }

  /** Retains job metadata even when its associated business record no longer exists. */
  @Transactional(readOnly = true)
  public Map<String, Object> detail(UUID id) {
    return jdbc.query(PROJECTION.formatted("select * from job where id=?"), (rs, n) -> view(rs), id)
        .stream().findFirst().orElseThrow(DomainException::missing);
  }

  private record WarningSummary(String model, Instant createdAt, AiProvider.Usage usage) {}

  private record RecentWarning(UUID jobId, String kind, WarningSummary warning) {}

  private List<RecentWarning> recentWarnings() {
    return jdbc.query(RECENT_WARNINGS, (rs, n) ->
        new RecentWarning(rs.getObject("id", UUID.class), rs.getString("kind"), warning(rs)));
  }

  private WarningSummary warning(ResultSet rs) throws SQLException {
    String warning = rs.getString("context_warning");
    if (warning == null) return null;
    var usage = new AiProvider.Usage(rs.getObject("prompt_tokens", Integer.class),
        rs.getObject("response_tokens", Integer.class), rs.getInt("context_tokens"),
        AiProvider.CapacitySource.valueOf(rs.getString("capacity_source")),
        rs.getString("done_reason"), AiProvider.ContextWarning.valueOf(warning));
    return new WarningSummary(rs.getString("warning_model"), instant(rs, "warning_at"), usage);
  }

  private Map<String, Object> view(ResultSet rs) throws SQLException {
    String kind = rs.getString("kind");
    String type = switch (kind) {
      case "REVIEW_INVOICE", "NORMALIZE_CATEGORIES", "CLASSIFY_INVOICE", "SUGGEST_RECONCILIATION" -> "invoice";
      case "REVIEW_MOVEMENT", "CLASSIFY_MOVEMENT" -> "movement";
      case "EXTRACT", "IMPORT_ZIP", "REVIEW_DOCUMENT", "AI_EXTRACT_DOCUMENT" -> "document";
      case "REVIEW_ALLOCATION" -> "allocation";
      case "REPAIR_ALERT" -> "alert";
      default -> "unknown";
    };
    boolean exists = !type.equals("unknown") && rs.getObject(type + "_id") != null;
    boolean available = exists && (Set.of("allocation", "alert").contains(type) || !rs.getBoolean(type + "_deleted"));
    String label = switch (type) {
      case "invoice" -> Objects.requireNonNullElse(rs.getString("invoice_number"), Objects.requireNonNullElse(rs.getString("supplier"), "Fatura"));
      case "movement" -> rs.getString("description");
      case "document" -> rs.getString("filename");
      case "alert" -> rs.getString("alert_message");
      case "allocation" -> "Associação de pagamento";
      default -> kind.equals("REFINE_CATEGORIES") ? "Revisão do catálogo de categorias" : null;
    };
    var target = map("type", type, "id", rs.getObject("target_id"),
        "label", Objects.requireNonNullElse(label, "Registo indisponível"), "available", available,
        "documentId", rs.getObject("original_id"));
    if (type.equals("allocation") && exists) {
      target.putAll(map("invoiceId", rs.getObject("allocation_invoice_id"),
          "movementId", rs.getObject("allocation_movement_id"),
          "invoiceAvailable", Boolean.FALSE.equals(rs.getObject("allocation_invoice_deleted")),
          "movementAvailable", Boolean.FALSE.equals(rs.getObject("allocation_movement_deleted")),
          "amount", rs.getBigDecimal("allocation_amount").toPlainString(),
          "active", rs.getBoolean("allocation_active"), "evidence", rs.getString("allocation_evidence")));
    }
    return map("id", rs.getObject("id"), "kind", kind, "targetId", rs.getObject("target_id"),
        "targetVersion", rs.getLong("target_version"), "state", rs.getString("state"),
        "attempts", rs.getInt("attempts"), "error", rs.getString("error"),
        "createdAt", instant(rs, "created_at"), "updatedAt", instant(rs, "updated_at"),
        "availableAt", instant(rs, "available_at"), "target", target, "contextWarning", warning(rs));
  }

  private Instant instant(ResultSet rs, String name) throws SQLException {
    var value = rs.getTimestamp(name);
    return value == null ? null : value.toInstant();
  }
}
