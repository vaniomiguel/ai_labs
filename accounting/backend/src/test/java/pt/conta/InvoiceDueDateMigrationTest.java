package pt.conta;

import static org.assertj.core.api.Assertions.*;
import static pt.conta.shared.Inputs.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.conta.shared.Json;

/** Upgrades saved invoice dates and proposals without rewriting original or historical evidence. */
class InvoiceDueDateMigrationTest {
  private EmbeddedPostgres postgres;
  private Flyway flyway;
  private JdbcTemplate jdbc;

  @BeforeEach
  void database() throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    var source = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
    Flyway.configure().dataSource(source).target("4").load().migrate();
    flyway = Flyway.configure().dataSource(source).target("5").load();
    jdbc = new JdbcTemplate(source);
  }

  @AfterEach
  void close() throws Exception {
    if (postgres != null) postgres.close();
  }

  private UUID document(String kind, Object draft, Object provenance) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into source_document(id,created_at,updated_at,kind,draft,field_provenance,text,storage_key) "
        + "values(?,now(),now(),?,?,?,'Original: vencimento 2026-02-01','original.pdf')",
        id, kind, draft == null ? null : Json.write(draft), Json.write(provenance));
    return id;
  }

  private UUID proposal(UUID document, String status, Map<String, Object> value) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into alert(id,created_at,updated_at,document_id,status,proposal) values(?,now(),now(),?,?,?)",
        id, document, status, Json.write(value));
    return id;
  }

  private String stored(String table, String column, UUID id) {
    return jdbc.queryForObject("select " + column + " from " + table + " where id=?", String.class, id);
  }

  @Test
  void removesColumnAndRootProvenanceWithoutReplacingMissingInvoiceDate() {
    var metadata = map("issuedOn", map("protected", true), "dueOn", map("protected", true));
    var draft = map("issuedOn", null, "dueOn", "2026-02-01", "supplier", "Saved supplier", "total", "123.45");
    UUID doc = document("INVOICE", draft, map("root", metadata));
    UUID invoice = UUID.randomUUID();
    jdbc.update("insert into invoice(id,created_at,updated_at,document_id,issued_on,due_on,total,field_provenance) "
        + "values(?,now(),now(),?,null,'2026-02-01',123.45,?)", invoice, doc, Json.write(metadata));

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

    assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
        + "where table_schema='public' and table_name='invoice' and column_name='due_on'", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select issued_on from invoice where id=?", Object.class, invoice)).isNull();
    assertThat(stored("invoice", "total", invoice)).isEqualTo("123.45");
    assertThat(Json.object(stored("invoice", "field_provenance", invoice))).containsOnlyKeys("issuedOn");
    draft.remove("dueOn");
    assertThat(Json.object(stored("source_document", "draft", doc))).isEqualTo(draft);
    assertThat(stored("source_document", "field_provenance", doc)).doesNotContain("dueOn");
    assertThat(stored("source_document", "text", doc)).isEqualTo("Original: vencimento 2026-02-01");
    assertThat(stored("source_document", "storage_key", doc)).isEqualTo("original.pdf");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void preservesIndependentReceiptOrderIdentitiesAndOtherOwnership() {
    var first = map("_itemId", "first", "issuedOn", "2026-01-15", "dueOn", "2026-02-01", "total", "10.00");
    var second = map("id", "second", "issuedOn", "2026-01-16", "due_on", null, "total", "20.00");
    var draft = map("invoices", List.of(first, second), "dueOn", null, "reference", "keep");
    var metadata = map("first", map("dueOn", map("protected", true), "total", map("origin", "HUMAN_EDIT")),
        "second", map("due_on", map("protected", true)), "_legacy", map("includeEmpty", true));
    UUID doc = document("INVOICE", draft, metadata);

    flyway.migrate();

    first.remove("dueOn");
    second.remove("due_on");
    draft.remove("dueOn");
    assertThat(Json.object(stored("source_document", "draft", doc))).isEqualTo(draft);
    assertThat(Json.object(stored("source_document", "field_provenance", doc))).isEqualTo(
        map("first", map("total", map("origin", "HUMAN_EDIT")), "second", Map.of(),
            "_legacy", map("includeEmpty", true)));
  }

  @Test
  void cleansOnlyOpenInvoiceProposalsIncludingDueDateEvidenceAndChanges() {
    UUID doc = document("INVOICE", Map.of(), Map.of());
    var value = map("documentVersion", 7, "draft", map("invoices", List.of(map("id", "child", "dueOn", "2026-02-01"))),
        "evidence", List.of(map("field", "invoices[0].dueOn", "quote", "Due 2026-02-01"),
            map("field", "invoices[0].issuedOn", "quote", "Emissão 2026-01-01")),
        "changes", List.of(map("field", "dueOn", "key", "child:dueOn")));
    UUID open = proposal(doc, "OPEN", value);
    UUID resolved = proposal(doc, "RESOLVED", value);
    UUID statement = document("STATEMENT", map("dueOn", "keep"), Map.of());
    UUID otherProposal = proposal(statement, "OPEN", value);

    flyway.migrate();

    var cleaned = Json.object(stored("alert", "proposal", open));
    assertThat(cleaned).containsEntry("documentVersion", 7);
    var draft = Json.object(Json.write(cleaned.get("draft")));
    assertThat(rows(draft, "invoices")).containsExactly(map("id", "child"));
    assertThat(rows(cleaned, "evidence")).containsExactly(
        map("field", "invoices[0].issuedOn", "quote", "Emissão 2026-01-01"));
    assertThat((List<?>) cleaned.get("changes")).isEmpty();
    assertThat(stored("alert", "proposal", resolved)).isEqualTo(Json.write(value));
    assertThat(stored("alert", "proposal", otherProposal)).isEqualTo(Json.write(value));
    assertThat(stored("source_document", "draft", statement)).isEqualTo("{\"dueOn\":\"keep\"}");
  }

  @Test
  void handlesMissingNullAndEmptyFieldsAndLeavesHistoricalRecordsUntouched() {
    UUID absent = document("INVOICE", null, Map.of());
    UUID empty = document("INVOICE", map("invoices", List.of()), Map.of());
    UUID jsonNull = document("INVOICE", Map.of(), Map.of());
    jdbc.update("update source_document set draft='null' where id=?", jsonNull);
    String history = "{\"dueOn\":\"2026-02-01\"}";
    UUID audit = UUID.randomUUID(), execution = UUID.randomUUID(), attempt = UUID.randomUUID();
    jdbc.update("insert into audit_event(id,created_at,updated_at,after_value) values(?,now(),now(),?)", audit, history);
    jdbc.update("insert into ai_execution(id,created_at,updated_at,response) values(?,now(),now(),?)", execution, history);
    jdbc.update("insert into extraction_attempt(id,created_at,updated_at,result) values(?,now(),now(),?)", attempt, history);

    flyway.migrate();

    assertThat(stored("source_document", "draft", absent)).isNull();
    assertThat(stored("source_document", "draft", empty)).isEqualTo("{\"invoices\":[]}");
    assertThat(stored("source_document", "draft", jsonNull)).isEqualTo("null");
    assertThat(stored("audit_event", "after_value", audit)).isEqualTo(history);
    assertThat(stored("ai_execution", "response", execution)).isEqualTo(history);
    assertThat(stored("extraction_attempt", "result", attempt)).isEqualTo(history);
  }
}
