package pt.conta.invoice;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A canonical invoice tag with editable historical aliases. */
@Entity
@Table(name = "category")
public class Category extends BaseEntity {
  public String name;

  @Column(columnDefinition = "text")
  public String aliases = "[]";

  public boolean deleted;
}
