package pt.conta.shared;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.Types.*;

/** A versioned application setting, including model roles and import mappings. */
@Entity
@Table(name = "app_setting")
public class AppSetting extends BaseEntity {
  public String name;

  @Column(columnDefinition = "text")
  public String value;
}
