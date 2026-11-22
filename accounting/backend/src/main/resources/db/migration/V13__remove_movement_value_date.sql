select pg_advisory_xact_lock(712690118);

-- Clean current movement data while retaining original statements and audit history.
create function migration_is_movement_value_date_finding(message text) returns boolean
language sql immutable as $$
  select coalesce(message in (
    'data ilegível em valueOn', 'data ilegível em value_on',
    'Falta evidência para confirmar valueOn.', 'Falta evidência para confirmar value_on.',
    'A IA sugeriu rever valueOn.', 'A IA sugeriu rever value_on.'
  ), false);
$$;

create function migration_remove_movement_value_dates(snapshot jsonb, member text) returns jsonb
language plpgsql immutable strict as $$
begin
  case jsonb_typeof(snapshot)
    when 'object' then
      return (
        select coalesce(jsonb_object_agg(key, migration_remove_movement_value_dates(value, key)), '{}'::jsonb)
        from jsonb_each(snapshot)
        where key not in ('valueOn', 'value_on')
      );
    when 'array' then
      return (
        select coalesce(jsonb_agg(migration_remove_movement_value_dates(value, member) order by position), '[]'::jsonb)
        from jsonb_array_elements(snapshot) with ordinality as entries(value, position)
        where not (member in ('changes', 'evidence', 'concerns')
          and (coalesce(value->>'field', '') ~ '(^|[.])(valueOn|value_on)$'
            or coalesce(value->>'path', '') ~ '(^|[.])(valueOn|value_on)$'))
          and not (member = 'issues' and jsonb_typeof(value) = 'string'
            and migration_is_movement_value_date_finding(value #>> '{}'))
          and not (member = 'concerns' and migration_is_movement_value_date_finding(value->>'message'))
      );
    else
      return snapshot;
  end case;
end;
$$;

update bank_movement
set field_provenance = migration_remove_movement_value_dates(field_provenance::jsonb, '')::text
where migration_remove_movement_value_dates(field_provenance::jsonb, '') is distinct from field_provenance::jsonb;

update source_document
set draft = migration_remove_movement_value_dates(draft::jsonb, '')::text,
    field_provenance = migration_remove_movement_value_dates(field_provenance::jsonb, '')::text
where kind = 'STATEMENT'
  and (migration_remove_movement_value_dates(draft::jsonb, '') is distinct from draft::jsonb
    or migration_remove_movement_value_dates(field_provenance::jsonb, '') is distinct from field_provenance::jsonb);

with cleaned as (
  select a.id,
    migration_remove_movement_value_dates(a.proposal::jsonb, '') as proposal,
    migration_remove_movement_value_dates(a.concerns::jsonb, 'concerns') as concerns,
    migration_is_movement_value_date_finding(a.message) as retired_message
  from alert a
  where a.status = 'OPEN' and (a.entity_type = 'movement' or exists (
    select 1 from source_document d where d.kind = 'STATEMENT'
      and (d.id = a.document_id or (a.entity_type = 'document' and d.id = a.entity_id))
  ))
)
update alert a set
  proposal = case when c.proposal is distinct from a.proposal::jsonb then c.proposal::text else a.proposal end,
  concerns = case when c.concerns is distinct from a.concerns::jsonb then c.concerns::text else a.concerns end,
  code = case when c.retired_message then coalesce(c.concerns->0->>'code', 'EXTRACTION_REVIEW') else a.code end,
  message = case when c.retired_message then coalesce(c.concerns->0->>'message', 'Confirma os dados extraídos.') else a.message end
from cleaned c
where a.id = c.id and (c.retired_message or c.proposal is distinct from a.proposal::jsonb
  or c.concerns is distinct from a.concerns::jsonb);

alter table bank_movement drop column value_on;
drop function migration_remove_movement_value_dates(jsonb, text);
drop function migration_is_movement_value_date_finding(text);
