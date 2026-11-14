package pt.conta.category;

import jakarta.persistence.*;
import java.util.UUID;
import pt.conta.shared.BaseEntity;

@Entity
@Table(name = "category_assignment")
public class CategoryAssignment extends BaseEntity {
  public UUID classificationId;
  public UUID categoryId;
  public String origin;
  public boolean rejected;
  public UUID executionId;
  @Column(columnDefinition = "text") public String evidence;
}
