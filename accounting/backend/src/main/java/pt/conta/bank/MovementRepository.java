package pt.conta.bank;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** Indexed bank-description search using PostgreSQL trigram similarity and literal references. */
public interface MovementRepository extends JpaRepository<BankMovement, UUID> {
  /** Returns matching identities while finance DTOs remain independent of JPA entities. */
  @Query(
      value =
          "select id from bank_movement where not deleted and (description ilike :literal or"
              + " description % :query or bank_reference ilike :literal)",
      nativeQuery = true)
  List<UUID> search(@Param("query") String query, @Param("literal") String literal);
}
