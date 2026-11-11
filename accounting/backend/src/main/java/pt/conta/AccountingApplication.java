package pt.conta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import pt.conta.shared.AccountingProperties;

/** Starts the accounting application independently of the repository's AI experiments. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AccountingProperties.class)
public class AccountingApplication {
  /** Starts HTTP services and the durable background worker. */
  public static void main(String[] args) {
    SpringApplication.run(AccountingApplication.class, args);
  }
}
