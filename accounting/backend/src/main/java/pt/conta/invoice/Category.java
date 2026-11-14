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
  public UUID parentId;
  public UUID mergedIntoId;
  public String origin = "MANUAL";
  public boolean protectedCategory = true;
  @Column(columnDefinition = "text") public String description;
  public String referenceCode;
  public String referenceName;
  public String referenceVersion;
  @Column(columnDefinition = "text") public String referenceSource;
}
