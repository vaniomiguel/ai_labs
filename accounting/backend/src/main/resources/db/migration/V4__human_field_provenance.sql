-- Provenance is independent of financial amounts and validation eligibility.
alter table invoice add column field_provenance text not null default '{}';
alter table bank_movement add column field_provenance text not null default '{}';
alter table source_document add column field_provenance text not null default '{}';

-- Old snapshots do not reliably identify the fields a person edited. Protect populated
-- values conservatively whenever any financial edit/confirmation establishes participation.
update invoice i set field_provenance=(
  select coalesce(jsonb_object_agg(key,jsonb_build_object('origin','HUMAN_LEGACY','protected',true,'actor',coalesce(i.author,'LEGACY'),'at',i.updated_at,'proposalId',null)),'{}'::jsonb)::text
  from jsonb_each(to_jsonb(i) || jsonb_build_object('lines',coalesce((select jsonb_agg(to_jsonb(l)) from invoice_line l where l.invoice_id=i.id),'[]'::jsonb)))
  where key=any(array['supplier','supplierTaxId','supplier_tax_id','number','type','issued_on','due_on','total','net','tax','discount','description','currency','related_invoice_id','lines'])
    and value<>'null'::jsonb and value<>'[]'::jsonb and value<>'""'::jsonb
)
where i.manually_confirmed or i.origin='MANUAL' or i.review_status in ('MANUAL','CORRECTED','EDITED')
  or exists(select 1 from audit_event a where a.entity_type='invoice' and a.entity_id=i.id and a.action in ('CREATE','UPDATE') and a.actor<>'SYSTEM');

-- Rename database keys to their public field names without changing recorded decisions.
update invoice set field_provenance=(select coalesce(jsonb_object_agg(case key when 'supplier_tax_id' then 'supplierTaxId' when 'issued_on' then 'issuedOn' when 'due_on' then 'dueOn' when 'related_invoice_id' then 'relatedInvoiceId' else key end,value),'{}'::jsonb)::text from jsonb_each(field_provenance::jsonb));

update bank_movement m set field_provenance=(
  select coalesce(jsonb_object_agg(case key when 'account_id' then 'accountId' when 'bank_reference' then 'bankReference' when 'booked_on' then 'bookedOn' when 'value_on' then 'valueOn' when 'occurred_at' then 'occurredAt' else key end,
    jsonb_build_object('origin','HUMAN_LEGACY','protected',true,'actor',coalesce(m.author,'LEGACY'),'at',m.updated_at,'proposalId',null)),'{}'::jsonb)::text
  from jsonb_each(to_jsonb(m)) where key=any(array['account_id','bank_reference','booked_on','value_on','occurred_at','description','amount','currency','direction','kind'])
    and value<>'null'::jsonb and value<>'""'::jsonb
)
where m.manually_confirmed or m.origin='MANUAL' or m.review_status in ('MANUAL','CORRECTED','EDITED')
  or exists(select 1 from audit_event a where a.entity_type='movement' and a.entity_id=m.id and a.action in ('CREATE','UPDATE') and a.actor<>'SYSTEM');

-- Recover deliberate clears where the old audit trail establishes them. Unchanged empty
-- optional values on a confirmed record remain unprotected.
create function migration_human_clears(target_type text,target_id uuid,snapshot jsonb) returns jsonb language sql stable as $$
  select coalesce(jsonb_object_agg(key,decision),'{}'::jsonb) from (
    select distinct on (e.key) e.key,jsonb_build_object('origin','HUMAN_LEGACY','protected',true,'actor',a.actor,'at',a.created_at,'proposalId',null) decision
    from audit_event a cross join lateral jsonb_each(case when jsonb_typeof(a.after_value::jsonb)='object' then a.after_value::jsonb else '{}'::jsonb end) e
    where a.entity_type=target_type and a.entity_id=target_id and a.actor<>'SYSTEM' and a.action in ('UPDATE','EDIT_EXTRACTION')
      and e.key=any(array['supplier','supplierTaxId','number','type','issuedOn','dueOn','total','net','tax','discount','description','currency','relatedInvoiceId','lines','accountId','bankReference','bookedOn','valueOn','occurredAt','amount','direction','kind'])
      and coalesce(snapshot->e.key,'null'::jsonb) in ('null'::jsonb,'""'::jsonb,'[]'::jsonb)
      and e.value in ('null'::jsonb,'""'::jsonb,'[]'::jsonb)
      and coalesce(a.before_value::jsonb->e.key,'null'::jsonb) not in ('null'::jsonb,'""'::jsonb,'[]'::jsonb)
    order by e.key,a.created_at desc,a.id desc
  ) changes
$$;

update invoice i set field_provenance=(i.field_provenance::jsonb||migration_human_clears('invoice',i.id,
  (select jsonb_object_agg(case key when 'supplier_tax_id' then 'supplierTaxId' when 'issued_on' then 'issuedOn' when 'due_on' then 'dueOn' when 'related_invoice_id' then 'relatedInvoiceId' else key end,value) from jsonb_each(to_jsonb(i)))
  ||jsonb_build_object('lines',coalesce((select jsonb_agg(to_jsonb(l)) from invoice_line l where l.invoice_id=i.id),'[]'::jsonb))))::text;
update bank_movement m set field_provenance=(m.field_provenance::jsonb||migration_human_clears('movement',m.id,
  (select jsonb_object_agg(case key when 'account_id' then 'accountId' when 'bank_reference' then 'bankReference' when 'booked_on' then 'bookedOn' when 'value_on' then 'valueOn' when 'occurred_at' then 'occurredAt' else key end,value) from jsonb_each(to_jsonb(m)))))::text;

update source_document d set field_provenance=jsonb_build_object('_legacy',jsonb_build_object('origin','HUMAN_LEGACY','actor','LEGACY','at',d.updated_at,'protected',true,
  'includeEmpty',exists(select 1 from audit_event a where a.entity_type='document' and a.entity_id=d.id and a.action='EDIT_EXTRACTION')))::text
where exists(select 1 from audit_event a where a.entity_type='document' and a.entity_id=d.id and a.action='EDIT_EXTRACTION')
  or exists(select 1 from invoice i where i.document_id=d.id and i.field_provenance<>'{}')
  or exists(select 1 from bank_movement m where m.document_id=d.id and m.field_provenance<>'{}');

update source_document d set field_provenance=(d.field_provenance::jsonb||
  coalesce((select jsonb_object_agg(case when case when jsonb_typeof(d.draft::jsonb->'invoices')='array' then jsonb_array_length(d.draft::jsonb->'invoices') else 0 end=0 then 'root' else i.id::text end,i.field_provenance::jsonb) from invoice i where i.document_id=d.id and not i.deleted),'{}'::jsonb)||
  coalesce((select jsonb_object_agg(m.id::text,m.field_provenance::jsonb) from bank_movement m where m.document_id=d.id and not m.deleted),'{}'::jsonb))::text
where d.kind in ('INVOICE','STATEMENT');
drop function migration_human_clears(text,uuid,jsonb);
