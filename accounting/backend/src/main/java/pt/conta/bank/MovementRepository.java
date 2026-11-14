package pt.conta.bank;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** Indexed bank-description search using PostgreSQL trigram similarity and literal references. */
public interface MovementRepository extends JpaRepository<BankMovement, UUID> {
  /** Returns matching identities while finance DTOs remain independent of JPA entities. */
  @Query(value = """
      select m.id from bank_movement m where not m.deleted and (m.description ilike :literal
        or m.description % :query or m.bank_reference ilike :literal
        or exists(select 1 from source_document d where d.id=m.document_id and d.filename ilike :literal))
      """, nativeQuery = true)
  List<UUID> search(@Param("query") String query, @Param("literal") String literal);
}
