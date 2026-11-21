package pt.conta;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.conta.shared.Json;

class AlertMigrationTest {
  @Test
  void mergesLegacyConcernsAndKeepsTheRepairTargetAndResolvedHistory() throws Exception {
    try (var postgres = EmbeddedPostgres.builder().setPort(0).start()) {
      var source = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
      Flyway.configure().dataSource(source).target("8").load().migrate();
      var jdbc = new JdbcTemplate(source);
      UUID document = UUID.randomUUID(), invoice = UUID.randomUUID(), confirmed = UUID.randomUUID();
      jdbc.update("insert into source_document(id,created_at,updated_at,kind,draft) values(?,now(),now(),'INVOICE',?)",
          document, Json.write(java.util.Map.of("id", invoice)));
      addInvoice(jdbc, invoice, document, false);
      addInvoice(jdbc, confirmed, null, true);
      UUID duplicate = addAlert(jdbc, document, "invoice", invoice, "INVALID_INVOICE", "OPEN", "Data em falta");
      UUID repair = addAlert(jdbc, document, "document", document, "EXTRACTION_REVIEW", "OPEN", "Total incerto");
      UUID history = addAlert(jdbc, document, "invoice", invoice, "OLD", "RESOLVED", "Histórico");
      UUID shared = addAlert(jdbc, document, "document", document, "AI_STRUCTURE_REVIEW", "OPEN", "Páginas em falta");
      UUID toClose = addAlert(jdbc, null, "invoice", confirmed, "AI_CONCERN", "OPEN", "Valor incerto");
      jdbc.update("insert into job(id,created_at,updated_at,target_id,kind,state,payload) "
          + "values (?,now(),now(),?,'REPAIR_ALERT','WAITING','{\"sourceVersion\":0}')", UUID.randomUUID(), repair);

      Flyway.configure().dataSource(source).load().migrate();

      assertThat(jdbc.queryForMap("select entity_id,status,concerns from alert where id=?", repair))
          .containsEntry("entity_id", invoice).containsEntry("status", "OPEN");
      assertThat(jdbc.queryForObject("select concerns from alert where id=?", String.class, repair))
          .contains("Data em falta", "Total incerto").doesNotContain("Histórico", "Páginas em falta");
      assertThat(jdbc.queryForMap("select merged_into,status from alert where id=?", duplicate))
          .containsEntry("merged_into", repair).containsEntry("status", "RESOLVED");
      assertThat(jdbc.queryForObject("select status from alert where id=?", String.class, history)).isEqualTo("RESOLVED");
      assertThat(jdbc.queryForObject("select status from alert where id=?", String.class, toClose)).isEqualTo("RESOLVED");
      assertThat(jdbc.queryForObject("select status from alert where id=?", String.class, shared)).isEqualTo("OPEN");
      assertThatThrownBy(() -> addAlert(jdbc, document, "invoice", invoice, "OTHER", "OPEN", "Duplicado"))
          .isInstanceOf(DuplicateKeyException.class);
    }
  }

  private void addInvoice(JdbcTemplate jdbc, UUID id, UUID document, boolean confirmed) {
    jdbc.update("insert into invoice(id,created_at,updated_at,document_id,manually_confirmed) values(?,now(),now(),?,?)",
        id, document, confirmed);
  }

  private UUID addAlert(JdbcTemplate jdbc, UUID document, String type, UUID target, String code, String status, String message) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into alert(id,created_at,updated_at,document_id,entity_type,entity_id,code,status,message) "
        + "values(?,now(),now(),?,?,?,?,?,?)", id, document, type, target, code, status, message);
    return id;
  }
}
