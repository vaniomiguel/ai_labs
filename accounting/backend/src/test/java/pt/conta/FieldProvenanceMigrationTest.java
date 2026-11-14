package pt.conta;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.conta.shared.Json;

class FieldProvenanceMigrationTest {
  @Test void migrationProtectsHistoricalHumanWorkWithoutChangingAmountsSourcesOrPostings()throws Exception {
    try(var postgres=EmbeddedPostgres.builder().setPort(0).start()) {
      var source=new DriverManagerDataSource(postgres.getJdbcUrl("postgres","postgres"),"postgres","postgres");
      Flyway.configure().dataSource(source).target("3").load().migrate();var jdbc=new JdbcTemplate(source);
      UUID doc=UUID.randomUUID(),invoice=UUID.randomUUID(),automatic=UUID.randomUUID(),movement=UUID.randomUUID();
      String draft="{\"supplier\":\"Humano\",\"total\":\"123.45\"}";
      jdbc.update("insert into source_document(id,created_at,updated_at,kind,draft,text,status) values(?,now(),now(),'INVOICE',?,'Original preservado','OPEN')",doc,draft);
      jdbc.update("insert into invoice(id,created_at,updated_at,document_id,supplier,total,issued_on,origin,review_status,manually_confirmed) values(?,now(),now(),?,'Humano',123.45,'2026-01-01','AI','OPEN',false)",invoice,doc);
      jdbc.update("insert into audit_event(id,created_at,updated_at,entity_type,entity_id,actor,action,before_value,after_value) values(?,now(),now(),'invoice',?,'gestor','UPDATE',?,?)",UUID.randomUUID(),invoice,"{\"description\":\"Limpar\"}","{\"description\":null}");
      jdbc.update("insert into invoice(id,created_at,updated_at,supplier,total,origin,review_status,manually_confirmed) values(?,now(),now(),'Automático',12.00,'AI','AUTOMATIC',false)",automatic);
      jdbc.update("insert into bank_movement(id,created_at,updated_at,description,amount,booked_on,direction,review_status,origin,manually_confirmed) values(?,now(),now(),'Movimento humano',25.00,'2026-01-02','DEBIT','MANUAL','MANUAL',true)",movement);
      jdbc.update("insert into ledger_entry(id,created_at,updated_at,source_type,source_id,effective_on,amount) values(?,now(),now(),'INVOICE',?,'2026-01-01',123.45)",UUID.randomUUID(),invoice);
      var flyway=Flyway.configure().dataSource(source).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
      var ownership=Json.object(jdbc.queryForObject("select field_provenance from invoice where id=?",String.class,invoice));
      assertThat(ownership).containsKeys("supplier","total","issuedOn","description").doesNotContainKey("supplierTaxId");
      assertThat(Json.read(Json.write(ownership)).path("supplier").path("protected").asBoolean()).isTrue();
      assertThat(jdbc.queryForObject("select field_provenance from invoice where id=?",String.class,automatic)).isEqualTo("{}");
      assertThat(Json.object(jdbc.queryForObject("select field_provenance from bank_movement where id=?",String.class,movement))).containsKeys("bookedOn","amount","description");
      assertThat(jdbc.queryForObject("select field_provenance from source_document where id=?",String.class,doc)).contains("HUMAN_LEGACY");
      assertThat(jdbc.queryForObject("select draft from source_document where id=?",String.class,doc)).isEqualTo(draft);
      assertThat(jdbc.queryForObject("select total::text from invoice where id=?",String.class,invoice)).isEqualTo("123.45");
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry",Integer.class)).isEqualTo(1);
      assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
  }
}
