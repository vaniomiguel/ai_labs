package pt.conta.category;

import jakarta.persistence.*;
import java.util.UUID;
import pt.conta.shared.BaseEntity;

/** Stores structural inverses; assignments keep their original identities through redirects. */
@Entity
@Table(name = "category_change")
public class CategoryChange extends BaseEntity {
  public String kind;
  public String actor;
  public UUID executionId;
  public long catalogRevision;
  public boolean undone;
  @Column(columnDefinition = "text") public String reason;
  @Column(columnDefinition = "text") public String evidence;
  @Column(columnDefinition = "text") public String beforeState;
  @Column(columnDefinition = "text") public String afterState;
}
