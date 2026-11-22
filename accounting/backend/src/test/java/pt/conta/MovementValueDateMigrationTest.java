package pt.conta;

import static org.assertj.core.api.Assertions.assertThat;
import static pt.conta.shared.Inputs.map;
import static pt.conta.shared.Inputs.rows;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.conta.shared.Json;

class MovementValueDateMigrationTest {
  private static final String RETIRED_ISSUE = "data ilegível em valueOn";
  private static final String RETIRED_SUGGESTION = "A IA sugeriu rever valueOn.";
  private static final String BOOKING_SUGGESTION = "A IA sugeriu rever bookedOn.";

  private EmbeddedPostgres postgres;
  private Flyway flyway;
  private JdbcTemplate jdbc;

  @BeforeEach
  void database() throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    var source = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
    Flyway.configure().dataSource(source).target("12").load().migrate();
    flyway = Flyway.configure().dataSource(source).target("13").load();
    jdbc = new JdbcTemplate(source);
  }

  @AfterEach
  void close() throws Exception {
    if (postgres != null) postgres.close();
  }

  @Test
  void dropsValueDatesAndPreservesBookingDatesMovementIdentityAndHistory() {
    UUID movement = UUID.randomUUID();
    var ownership = map("amount", map("protected", true), "valueOn", map("protected", true));
    var row = map("id", movement.toString(), "bookedOn", null, "valueOn", "2026-02-04", "description", "Compra",
        "amount", "12.00", "issues", List.of(RETIRED_ISSUE, "Falta a data do movimento"));
    var next = map("_itemId", "second", "bookedOn", "2026-02-05", "value_on", "2026-02-06", "field", "value_on");
    var draft = map("movements", List.of(row, next), "validate", false);
    UUID document = document("STATEMENT", draft, map(movement.toString(), ownership));
    jdbc.update("""
        insert into bank_movement (id, created_at, updated_at, document_id, value_on, amount, review_status, field_provenance)
        values (?, now(), now(), ?, '2026-02-04', 12.00, 'OPEN', ?)
        """, movement, document, Json.write(ownership));
    migrateAndVerifyHistoricalSnapshots(Json.write(draft));

    assertThat(jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where table_schema = 'public' and table_name = 'bank_movement' and column_name = 'value_on'
        """, Integer.class)).isZero();
    assertThat(stored("bank_movement", "booked_on", movement)).isNull();
    assertThat(stored("bank_movement", "review_status", movement)).isEqualTo("OPEN");
    assertThat(stored("bank_movement", "amount", movement)).isEqualTo("12.00");
    ownership.remove("valueOn");
    assertThat(Json.object(stored("bank_movement", "field_provenance", movement))).isEqualTo(ownership);
    assertThat(Json.object(stored("source_document", "field_provenance", document)))
        .isEqualTo(map(movement.toString(), ownership));
    row.remove("valueOn");
    row.put("issues", List.of("Falta a data do movimento"));
    next.remove("value_on");
    assertThat(Json.object(stored("source_document", "draft", document))).isEqualTo(draft);
    assertThat(stored("source_document", "text", document)).isEqualTo("Original com duas datas");
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void removesOnlyRetiredFindingsFromOpenMovementRepairs() {
    UUID statement = document("STATEMENT", Map.of(), Map.of());
    UUID invoice = document("INVOICE", map("valueOn", "unrelated"), Map.of());
    var keptEvidence = map("field", "movements[0].bookedOn", "quote", "02/02/2026");
    var keptChange = map("field", "bookedOn", "key", "child:bookedOn");
    var keptConcern = map("code", "AI_FIELD_SUGGESTIONS", "message", BOOKING_SUGGESTION);
    var retiredConcern = map("code", "AI_FIELD_SUGGESTIONS", "message", RETIRED_SUGGESTION);
    var proposal = map("documentVersion", 7, "draft", map("movements",
        List.of(map("id", "child", "bookedOn", "2026-02-02", "valueOn", "2026-02-04"))),
        "issues", List.of(RETIRED_ISSUE, "Rever descrição"),
        "evidence", List.of(map("path", "movements[0].value_on", "quote", "04/02/2026"), keptEvidence),
        "changes", List.of(map("field", "valueOn", "key", "child:valueOn"), keptChange));
    UUID open = alert(statement, "OPEN", proposal, List.of(retiredConcern, keptConcern));
    UUID retiredOnly = alert(statement, "OPEN", null, List.of(retiredConcern));
    UUID historical = alert(statement, "RESOLVED", proposal, List.of(retiredConcern));
    UUID unrelated = alert(invoice, "OPEN", proposal, List.of(retiredConcern));

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

    var cleaned = Json.object(stored("alert", "proposal", open));
    assertThat(cleaned).containsEntry("documentVersion", 7).containsEntry("issues", List.of("Rever descrição"));
    assertThat(rows(Json.object(Json.write(cleaned.get("draft"))), "movements"))
        .containsExactly(map("id", "child", "bookedOn", "2026-02-02"));
    assertThat(rows(cleaned, "evidence")).containsExactly(keptEvidence);
    assertThat(rows(cleaned, "changes")).containsExactly(keptChange);
    assertThat(stored("alert", "message", open)).isEqualTo(BOOKING_SUGGESTION);
    assertThat(Json.value(stored("alert", "concerns", open))).isEqualTo(List.of(keptConcern));
    assertThat(stored("alert", "status", retiredOnly)).isEqualTo("OPEN");
    assertThat(stored("alert", "message", retiredOnly)).isEqualTo("Confirma os dados extraídos.");
    assertThat(stored("alert", "proposal", historical)).isEqualTo(Json.write(proposal));
    assertThat(stored("alert", "proposal", unrelated)).isEqualTo(Json.write(proposal));
    assertThat(Json.object(stored("source_document", "draft", invoice))).containsEntry("valueOn", "unrelated");
  }

  private void migrateAndVerifyHistoricalSnapshots(String snapshot) {
    UUID audit = UUID.randomUUID(), execution = UUID.randomUUID(), attempt = UUID.randomUUID();
    jdbc.update("insert into audit_event(id,created_at,updated_at,after_value) values(?,now(),now(),?)", audit, snapshot);
    jdbc.update("insert into ai_execution(id,created_at,updated_at,response) values(?,now(),now(),?)", execution, snapshot);
    jdbc.update("insert into extraction_attempt(id,created_at,updated_at,result) values(?,now(),now(),?)", attempt, snapshot);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

    assertThat(stored("audit_event", "after_value", audit)).isEqualTo(snapshot);
    assertThat(stored("ai_execution", "response", execution)).isEqualTo(snapshot);
    assertThat(stored("extraction_attempt", "result", attempt)).isEqualTo(snapshot);
  }

  private UUID document(String kind, Map<String, Object> draft, Map<String, Object> ownership) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into source_document (id, created_at, updated_at, kind, draft, field_provenance, text)
        values (?, now(), now(), ?, ?, ?, 'Original com duas datas')
        """, id, kind, Json.write(draft), Json.write(ownership));
    return id;
  }

  private UUID alert(UUID document, String status, Map<String, Object> proposal, List<Map<String, Object>> concerns) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into alert (id, created_at, updated_at, document_id, status, code, message, proposal, concerns)
        values (?, now(), now(), ?, ?, 'AI_FIELD_SUGGESTIONS', ?, ?, ?)
        """, id, document, status, RETIRED_SUGGESTION, proposal == null ? null : Json.write(proposal), Json.write(concerns));
    return id;
  }

  private String stored(String table, String column, UUID id) {
    return jdbc.queryForObject("select " + column + " from " + table + " where id = ?", String.class, id);
  }
}
