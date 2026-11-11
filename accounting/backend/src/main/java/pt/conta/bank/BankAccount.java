package pt.conta.bank;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A company bank account; only EUR is supported by this release. */
@Entity
@Table(name = "bank_account")
public class BankAccount extends BaseEntity {
  public String name;
  public String iban;
  public String currency = "EUR";
  public boolean deleted;
}
