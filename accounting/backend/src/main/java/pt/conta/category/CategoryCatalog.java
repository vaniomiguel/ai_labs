package pt.conta.category;

import jakarta.persistence.*;
import java.util.UUID;
import pt.conta.shared.BaseEntity;

@Entity
@Table(name = "category_catalog")
public class CategoryCatalog extends BaseEntity {
  public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  public long revision = 1;
}
