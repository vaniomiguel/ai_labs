alter table alert add column dismissed_at timestamptz;

drop index alert_one_open_record;
create unique index alert_one_open_record on alert (entity_type, entity_id)
where status = 'OPEN' and dismissed_at is null and entity_type in ('invoice', 'movement');

-- Keep the approval requirement when its alert and findings are permanently removed.
create table pending_financial_review (
  entity_type varchar(255) not null,
  entity_id uuid not null,
  primary key (entity_type, entity_id)
);
