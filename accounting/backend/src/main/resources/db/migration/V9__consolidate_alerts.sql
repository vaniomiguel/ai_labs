select pg_advisory_xact_lock(712690118);

alter table alert add column concerns text not null default '[]';
alter table alert add column merged_into uuid references alert(id);

update invoice set manually_confirmed = true
where not manually_confirmed and review_status in ('MANUAL', 'CORRECTED', 'EDITED');
update bank_movement set manually_confirmed = true
where not manually_confirmed and review_status in ('MANUAL', 'CORRECTED', 'EDITED');

update alert
set concerns = (
  select coalesce(jsonb_agg(jsonb_build_object('code', code, 'message', entry)), '[]'::jsonb)::text
  from unnest(case when code in ('INVALID_INVOICE', 'INVALID_MOVEMENT', 'INVALID_REINCLUDED_RECORD', 'STATEMENT_INTEGRITY')
    then string_to_array(message, '; ') else array[message] end) as entries(entry)
)
where code is not null and message is not null;

-- A root invoice with an explicit persisted ID uniquely owns its extraction and draft concerns.
update alert a set entity_type = 'invoice', entity_id = i.id
from source_document d join invoice i on i.document_id = d.id
where a.entity_type = 'document' and a.entity_id = d.id and a.status = 'OPEN'
  and a.code in ('EXTRACTION_REVIEW', 'DRAFT_REVIEW', 'AI_FIELD_SUGGESTIONS') and d.kind = 'INVOICE'
  and d.draft::jsonb ->> 'id' = i.id::text and not i.deleted
  and not exists (select 1 from invoice sibling
    where sibling.document_id = d.id and sibling.id <> i.id and not sibling.deleted);

-- The old duplicate warning had only document scope. A persisted singleton identifies its row.
update alert a set entity_type = 'movement', entity_id = m.id
from source_document d join bank_movement m on m.document_id = d.id
where a.entity_type = 'document' and a.entity_id = d.id and a.status = 'OPEN'
  and a.code = 'POSSIBLE_DUPLICATE' and d.kind = 'STATEMENT'
  and d.draft::jsonb -> 'movements' -> 0 ->> 'id' = m.id::text and not m.deleted
  and not exists (select 1 from bank_movement sibling
    where sibling.document_id = d.id and sibling.id <> m.id and not sibling.deleted);

-- Keep the alert carrying the latest current repair when possible. Archived rows retain job links.
with ranked as (
  select a.id, first_value(a.id) over (
    partition by a.entity_type, a.entity_id
    order by exists (select 1 from job j join source_document source on source.id = a.document_id
      where j.kind = 'REPAIR_ALERT' and j.target_id = a.id
        and j.state in ('QUEUED', 'RUNNING', 'WAITING', 'DONE')
        and j.payload::jsonb ->> 'sourceVersion' = source.version::text) desc,
      (a.proposal is not null) desc, a.created_at, a.id
  ) as kept
  from alert a
  where a.status = 'OPEN' and (a.entity_type in ('invoice', 'movement')
    or a.entity_type = 'document' and exists (select 1 from source_document d
      where d.id = a.entity_id and d.kind in ('INVOICE', 'STATEMENT')))
), combined as (
  select r.kept, jsonb_agg(distinct concern order by concern) as concerns
  from ranked r join alert a on a.id = r.id
  cross join lateral jsonb_array_elements(a.concerns::jsonb) as entries(concern)
  group by r.kept
), archived as (
  update alert a set merged_into = r.kept, status = 'RESOLVED',
    resolved_at = now(), updated_at = now(), resolved_by = 'SYSTEM', version = a.version + 1
  from ranked r where r.id = a.id and r.id <> r.kept
)
update alert a set concerns = c.concerns::text
from combined c where a.id = c.kept;

update alert a set status = 'RESOLVED', resolved_at = now(), updated_at = now(),
  resolved_by = 'SYSTEM', version = a.version + 1
where a.status = 'OPEN' and (
  a.entity_type = 'invoice' and exists (select 1 from invoice i
    where i.id = a.entity_id and (i.manually_confirmed or i.deleted))
  or a.entity_type = 'movement' and exists (select 1 from bank_movement m
    where m.id = a.entity_id and (m.manually_confirmed or m.deleted))
);

create unique index alert_one_open_record on alert (entity_type, entity_id)
where status = 'OPEN' and entity_type in ('invoice', 'movement');
