-- Retire current due-date fields while preserving immutable source and execution history.
create function migration_remove_invoice_due_dates(snapshot jsonb) returns jsonb
language plpgsql immutable strict as $$
begin
  case jsonb_typeof(snapshot)
    when 'object' then
      return (
        select coalesce(jsonb_object_agg(key, migration_remove_invoice_due_dates(value)), '{}'::jsonb)
        from jsonb_each(snapshot)
        where key not in ('dueOn', 'due_on')
      );
    when 'array' then
      return (
        select coalesce(jsonb_agg(migration_remove_invoice_due_dates(value) order by position), '[]'::jsonb)
        from jsonb_array_elements(snapshot) with ordinality as entries(value, position)
        where coalesce(value->>'field', value->>'path', '') !~ '(^|[.])(dueOn|due_on)$'
      );
    else
      return snapshot;
  end case;
end;
$$;

update invoice
set field_provenance = migration_remove_invoice_due_dates(field_provenance::jsonb)::text
where migration_remove_invoice_due_dates(field_provenance::jsonb) is distinct from field_provenance::jsonb;

update source_document
set draft = migration_remove_invoice_due_dates(draft::jsonb)::text,
    field_provenance = migration_remove_invoice_due_dates(field_provenance::jsonb)::text
where kind = 'INVOICE'
  and (migration_remove_invoice_due_dates(draft::jsonb) is distinct from draft::jsonb
    or migration_remove_invoice_due_dates(field_provenance::jsonb) is distinct from field_provenance::jsonb);

update alert a
set proposal = migration_remove_invoice_due_dates(a.proposal::jsonb)::text
from source_document d
where a.document_id = d.id and d.kind = 'INVOICE' and a.status = 'OPEN'
  and migration_remove_invoice_due_dates(a.proposal::jsonb) is distinct from a.proposal::jsonb;

alter table invoice drop column due_on;
drop function migration_remove_invoice_due_dates(jsonb);
