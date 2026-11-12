-- Reconciliation links no longer post money: each eligible source contributes exactly once.
-- Keep every previous posting, reversing it on its original business date.
select pg_advisory_xact_lock(712690118);

alter table invoice add column excluded boolean not null default false;
alter table bank_movement add column excluded boolean not null default false;

insert into ledger_entry
  (id,version,created_at,updated_at,source_type,source_id,source_version,effective_on,
   amount,reverses_id,explanation)
select gen_random_uuid(),0,now(),now(),e.source_type,e.source_id,e.source_version,e.effective_on,
       -e.amount,e.id,'Anulação: atualização das regras de reconciliação'
from ledger_entry e
where e.source_type in ('ALLOCATION','MOVEMENT') and e.reverses_id is null
  and not exists (select 1 from ledger_entry r where r.reverses_id=e.id);

insert into ledger_entry
  (id,version,created_at,updated_at,source_type,source_id,source_version,effective_on,
   amount,reverses_id,explanation)
select gen_random_uuid(),0,now(),now(),'MOVEMENT',m.id,m.version,m.booked_on,
       case when m.direction='DEBIT' then -m.amount else m.amount end,
       null,'Movimento bancário: ' || m.direction
from bank_movement m
where not m.deleted and not m.excluded and m.review_status <> 'OPEN'
  and m.currency='EUR' and m.booked_on is not null and m.amount > 0
  and m.direction in ('DEBIT','CREDIT');
