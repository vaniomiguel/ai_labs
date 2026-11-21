package pt.conta;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import pt.conta.shared.Json;

class CategoryMigrationTest {
  @Test void existingNamesAliasesIdentitiesAndFinancialPostingsSurviveTheCategoryMigration() throws Exception {
    try(var postgres=EmbeddedPostgres.builder().setPort(0).start()) {
      var source=new DriverManagerDataSource(postgres.getJdbcUrl("postgres","postgres"),"postgres","postgres");
      Flyway.configure().dataSource(source).target("2").load().migrate();
      var jdbc=new JdbcTemplate(source);
      UUID category=UUID.randomUUID(),invoice=UUID.randomUUID(),movement=UUID.randomUUID(),posting=UUID.randomUUID(),legacyJob=UUID.randomUUID();
      jdbc.update("insert into category(id,created_at,updated_at,name,aliases) values(?,now(),now(),'Economato','[\"Material de escritorio\"]')",category);
      jdbc.update("insert into invoice(id,created_at,updated_at,issued_on,total,currency,type,review_status) values(?,now(),now(),'2026-01-01',100,'EUR','INVOICE','MANUAL')",invoice);
      jdbc.update("insert into invoice_category(invoice_id,category_id) values(?,?)",invoice,category);
      jdbc.update("insert into bank_movement(id,created_at,updated_at,booked_on,amount,currency,direction,review_status) values(?,now(),now(),'2026-01-02',50,'EUR','DEBIT','AUTOMATIC')",movement);
      jdbc.update("insert into ledger_entry(id,created_at,updated_at,source_type,source_id,effective_on,amount) values(?,now(),now(),'INVOICE',?,'2026-01-01',100)",posting,invoice);
      jdbc.update("insert into job(id,created_at,updated_at,kind,target_id,target_version,dedupe_key,state,attempts,payload) values(?,now(),now(),'NORMALIZE_CATEGORIES',?,0,'legacy-category-job','WAITING',0,'{}')",legacyJob,invoice);
      var flyway=Flyway.configure().dataSource(source).target("3").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
      var row=jdbc.queryForMap("select * from category where id=?",category);
      assertThat(row).containsEntry("name","Economato").containsEntry("aliases","[\"Material de escritorio\"]")
          .containsEntry("origin","LEGACY").containsEntry("protected_category",true).containsEntry("reference_code","6233");
      assertThat(jdbc.queryForObject("select count(*) from category where reference_code='6233'",Integer.class)).isEqualTo(1);
      UUID materials=jdbc.queryForObject("select id from category where reference_code='623'",UUID.class);
      assertThat(row).containsEntry("parent_id",materials);
      assertThat(jdbc.queryForObject("select category_id from category_assignment a join record_classification r on r.id=a.classification_id where r.invoice_id=?",UUID.class,invoice)).isEqualTo(category);
      assertThat(jdbc.queryForObject("select origin from category_assignment where category_id=?",String.class,category)).isEqualTo("LEGACY");
      assertThat(jdbc.queryForObject("select count(*) from job where id=? and kind='NORMALIZE_CATEGORIES' and state='WAITING'",Integer.class,legacyJob)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from job where target_id=? and kind='CLASSIFY_MOVEMENT'",Integer.class,movement)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry",Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select amount from ledger_entry where id=?",BigDecimal.class,posting)).isEqualByComparingTo("100.00");
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(jdbc.queryForObject("select count(*) from category_assignment",Integer.class)).isEqualTo(1);

      Flyway.configure().dataSource(source).target("5").load().migrate();
      UUID edited = jdbc.queryForObject("select id from category where reference_code='626'", UUID.class);
      jdbc.update("update category set name='Serviços da empresa',version=1 where id=?", edited);
      jdbc.update("insert into category(id,created_at,updated_at,name,aliases) values(?,now(),now(),?,?)",
          UUID.randomUUID(), "Contratação externa", "[\"Subcontratos\"]");
      var expanded = Flyway.configure().dataSource(source).load();
      assertThat(expanded.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(expanded.migrate().migrationsExecuted).isZero();
      assertExpandedReference(jdbc, edited);
      assertThat(jdbc.queryForObject("select category_id from category_assignment", UUID.class)).isEqualTo(category);
      assertThat(jdbc.queryForObject("select name from category where id=?", String.class, category)).isEqualTo("Economato");
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry", Integer.class)).isEqualTo(1);
    }
  }

  private void assertExpandedReference(JdbcTemplate jdbc, UUID edited) throws Exception {
    var reference = Json.read(new ClassPathResource("category-reference.json").getContentAsString(StandardCharsets.UTF_8));
    assertThat(reference.path("categories").size()).isEqualTo(60);
    assertThat(jdbc.queryForObject("select count(*) from category where reference_code is not null", Integer.class))
        .isEqualTo(60);
    for (var entry : reference.path("categories")) {
      String code = entry.path("code").asText();
      var row = jdbc.queryForMap("""
          select c.id,c.name,c.reference_name,c.protected_category,p.reference_code as parent_code
          from category c left join category p on p.id=c.parent_id where c.reference_code=?
          """, code);
      assertThat(row).containsEntry("reference_name", entry.path("referenceName").asText(entry.path("name").asText()))
          .containsEntry("protected_category", true);
      if (edited.equals(row.get("id"))) {
        assertThat(row).containsEntry("name", "Serviços da empresa").containsEntry("parent_code", null);
      } else {
        assertThat(row).containsEntry("parent_code", entry.path("parentCode").asText(null));
      }
    }
    assertThat(jdbc.queryForObject("select name from category where reference_code='621'", String.class))
        .isEqualTo("Subcontratos · SNC 621");
  }
}
