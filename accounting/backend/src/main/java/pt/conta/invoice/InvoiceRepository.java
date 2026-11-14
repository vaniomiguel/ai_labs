package pt.conta.invoice;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** PostgreSQL full-text lookup across invoice headers and their archived source text. */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
  /** Uses indexed Portuguese text search, with literal substring fallback for references. */
  @Query(
      value =
          """
          select i.id from invoice i where not i.deleted and (
            to_tsvector('portuguese',coalesce(i.supplier,'') || ' ' || coalesce(i.description,'') || ' ' || coalesce(i.number,'')) @@ plainto_tsquery('portuguese',:query)
            or i.supplier ilike :literal or i.number ilike :literal or i.description ilike :literal
            or exists(select 1 from source_document d where d.id=i.document_id and
              (d.filename ilike :literal or to_tsvector('portuguese',d.text) @@ plainto_tsquery('portuguese',:query) or d.text ilike :literal)))
          """,
      nativeQuery = true)
  List<UUID> search(@Param("query") String query, @Param("literal") String literal);
}
