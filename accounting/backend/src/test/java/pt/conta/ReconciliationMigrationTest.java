package pt.conta;

import static org.assertj.core.api.Assertions.*;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Upgrades a populated v1 database rather than testing the new schema only on an empty install. */
class ReconciliationMigrationTest {
  @Test
  void upgradePreservesHistoryAndLinksWhilePostingFullMovementsExactlyOnce() throws Exception {
    try (var postgres = EmbeddedPostgres.builder().setPort(0).start()) {
      var source = new DriverManagerDataSource(postgres.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
      Flyway.configure().dataSource(source).target("1").load().migrate();
      var jdbc = new JdbcTemplate(source);
      UUID invoice = UUID.randomUUID(), debit = UUID.randomUUID(), credit = UUID.randomUUID(), transfer = UUID.randomUUID();
      UUID allocation = UUID.randomUUID(), invoicePosting = UUID.randomUUID(), allocationPosting = UUID.randomUUID(), transferPosting = UUID.randomUUID();
      jdbc.update("insert into invoice(id,created_at,updated_at,issued_on,total,currency,type,review_status) values(?,now(),now(),'2025-12-10',100,'EUR','INVOICE','MANUAL')", invoice);
      movement(jdbc, debit, "2026-02-12", "120.00", "DEBIT", "UNCLASSIFIED", "MANUAL");
      movement(jdbc, credit, "2026-02-13", "25.00", "CREDIT", "OTHER", "AUTOMATIC");
      movement(jdbc, transfer, "2026-02-14", "10.00", "DEBIT", "REIMBURSEMENT", "MANUAL");
      movement(jdbc, UUID.randomUUID(), "2026-02-14", "999.00", "DEBIT", "UNCLASSIFIED", "OPEN");
      jdbc.update("insert into allocation(id,created_at,updated_at,invoice_id,movement_id,amount,active,origin) values(?,now(),now(),?,?,100,true,'MANUAL')", allocation, invoice, debit);
      posting(jdbc, invoicePosting, "INVOICE", invoice, "2025-12-10", "100.00");
      posting(jdbc, allocationPosting, "ALLOCATION", allocation, "2026-02-12", "-100.00");
      posting(jdbc, transferPosting, "MOVEMENT", transfer, "2026-02-14", "-10.00");
      Flyway flyway = Flyway.configure().dataSource(source).load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry where id in (?,?,?)", Integer.class, invoicePosting, allocationPosting, transferPosting)).isEqualTo(3);
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry where reverses_id in (?,?)", Integer.class, allocationPosting, transferPosting)).isEqualTo(2);
      assertThat(jdbc.queryForObject("select active from allocation where id=?", Boolean.class, allocation)).isTrue();
      assertThat(jdbc.queryForObject("select excluded from invoice where id=?", Boolean.class, invoice)).isFalse();
      assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where effective_on between '2026-01-01' and '2026-12-31'", BigDecimal.class)).isEqualByComparingTo("-105.00");
      assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry where effective_on < '2026-01-01'", BigDecimal.class)).isEqualByComparingTo("100.00");
      int postings = jdbc.queryForObject("select count(*) from ledger_entry", Integer.class);
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(jdbc.queryForObject("select count(*) from ledger_entry", Integer.class)).isEqualTo(postings);
      // The application must recognize the migrated postings as current, rather than replace
      // them again or bring back the old allocation-based financial effects.
      var application = new org.springframework.boot.SpringApplication(AccountingApplication.class);
      application.setRegisterShutdownHook(false);
      try (var context = application.run("--spring.profiles.active=test", "--server.port=0",
          "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
          "--accounting.storage=" + java.nio.file.Files.createTempDirectory("conta-migration-documents"),
          "--spring.datasource.username=postgres", "--spring.datasource.password=postgres")) {
        var book = context.getBean(pt.conta.ledger.BookkeepingService.class);
        book.synchronize();
        book.synchronize();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry", Integer.class)).isEqualTo(postings);
        assertThat(context.getBean(pt.conta.ledger.LedgerService.class).year(2026).get(1).get("closing")).isEqualTo("-105.00");
      }
    }
  }

  private void movement(JdbcTemplate jdbc, UUID id, String date, String amount, String direction, String kind, String status) {
    jdbc.update("insert into bank_movement(id,created_at,updated_at,booked_on,amount,currency,direction,kind,review_status) values(?,now(),now(),?::date,?,'EUR',?,?,?)",
        id, date, new BigDecimal(amount), direction, kind, status);
  }

  private void posting(JdbcTemplate jdbc, UUID id, String type, UUID source, String date, String amount) {
    jdbc.update("insert into ledger_entry(id,created_at,updated_at,source_type,source_id,effective_on,amount) values(?,now(),now(),?,?,?::date,?)",
        id, type, source, date, new BigDecimal(amount));
  }
}
