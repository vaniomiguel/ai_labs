select pg_advisory_xact_lock(712690118);
alter table category add column parent_id uuid references category(id);
alter table category add column merged_into_id uuid references category(id);
alter table category add column origin varchar(255) not null default 'LEGACY';
alter table category add column protected_category boolean not null default true;
alter table category add column description text;
alter table category add column reference_code varchar(255);
alter table category add column reference_name varchar(255);
alter table category add column reference_version varchar(255);
alter table category add column reference_source text;
create index category_parent_idx on category(parent_id);

create table category_catalog (
 id uuid primary key, version bigint not null default 0, created_at timestamptz not null,
 updated_at timestamptz not null, revision bigint not null default 1
);
insert into category_catalog(id,created_at,updated_at) values('00000000-0000-0000-0000-000000000003',now(),now());

create table record_classification (
 id uuid primary key, version bigint not null default 0, created_at timestamptz not null,
 updated_at timestamptz not null, invoice_id uuid unique references invoice(id) on delete cascade,
 movement_id uuid unique references bank_movement(id) on delete cascade,
 generation bigint not null default 0, status varchar(255) not null default 'PENDING',
 content_hash varchar(255), inheritance_hash varchar(255), applied_fingerprint varchar(255),
 hints text not null default '[]', reason text,
 check ((invoice_id is not null)::int + (movement_id is not null)::int = 1)
);
create table category_assignment (
 id uuid primary key, version bigint not null default 0, created_at timestamptz not null,
 updated_at timestamptz not null, classification_id uuid not null references record_classification(id) on delete cascade,
 category_id uuid not null references category(id), origin varchar(255) not null, rejected boolean not null default false,
 execution_id uuid, evidence text,
 unique(classification_id,category_id,origin,rejected),
 check (not rejected or origin='MANUAL')
);
create index category_assignment_category_idx on category_assignment(category_id);
create table category_change (
 id uuid primary key, version bigint not null default 0, created_at timestamptz not null,
 updated_at timestamptz not null, kind varchar(255), actor varchar(255), execution_id uuid,
 catalog_revision bigint not null, undone boolean not null default false, reason text,
 evidence text, before_state text, after_state text
);

-- Existing categories keep their IDs, names, aliases and human protection.
create temporary table category_seed(code text,name text,parent_code text) on commit drop;
insert into category_seed values
('622','Serviços especializados',null),
('6221','Trabalhos especializados','622'),
('6222','Publicidade e propaganda','622'),
('6223','Vigilância e segurança','622'),
('6224','Honorários','622'),
('6225','Comissões','622'),
('6226','Conservação e reparação','622'),
('623','Materiais',null),
('6231','Ferramentas e utensílios de desgaste rápido','623'),
('6232','Livros e documentação técnica','623'),
('6233','Material de escritório','623'),
('624','Energia e fluidos',null),
('6241','Eletricidade','624'),
('6242','Combustíveis','624'),
('6243','Água','624'),
('625','Deslocações, estadas e transportes',null),
('6251','Deslocações e estadas','625'),
('6252','Transportes de pessoal','625'),
('6253','Transportes de mercadorias','625'),
('626','Serviços diversos',null),
('6261','Rendas e alugueres','626'),
('6262','Comunicação','626'),
('6263','Seguros','626'),
('6264','Royalties','626'),
('6265','Contencioso e notariado','626'),
('6266','Despesas de representação','626'),
('6267','Limpeza, higiene e conforto','626'),
('63','Gastos com o pessoal',null),
('681','Impostos',null),
('6813','Taxas','681'),
('69','Gastos de financiamento',null),
('691','Juros suportados','69');
create function pg_temp.category_name_key(value text) returns text language sql immutable as $$
 select trim(regexp_replace(translate(lower(coalesce(value,'')),
 'áàâãäéèêëíìîïóòôõöúùûüç','aaaaaeeeeiiiiooooouuuuc'),'[^a-z0-9]+',' ','g'))
$$;
update category c set reference_code=s.code, reference_name=s.name,
 reference_version='OCC-SNC-2019-02', reference_source='https://portal.occ.pt/fotos/editor2/taxonomiasplanocontas_fev2019.pdf'
from category_seed s where not c.deleted and
 (pg_temp.category_name_key(c.name)=pg_temp.category_name_key(s.name) or exists(
 select 1 from jsonb_array_elements_text(case when jsonb_typeof(c.aliases::jsonb)='array' then c.aliases::jsonb else '[]'::jsonb end) a
 where pg_temp.category_name_key(a)=pg_temp.category_name_key(s.name)));
insert into category(id,created_at,updated_at,name,aliases,deleted,origin,protected_category,
 reference_code,reference_name,reference_version,reference_source)
select md5('category:snc:'||s.code)::uuid,now(),now(),s.name,'[]',false,'REFERENCE',true,
 s.code,s.name,'OCC-SNC-2019-02','https://portal.occ.pt/fotos/editor2/taxonomiasplanocontas_fev2019.pdf'
from category_seed s
where not exists (
 select 1 from category c where not c.deleted and (
  pg_temp.category_name_key(c.name)=pg_temp.category_name_key(s.name)
  or exists (
   select 1 from jsonb_array_elements_text(case when jsonb_typeof(c.aliases::jsonb)='array' then c.aliases::jsonb else '[]'::jsonb end) a
   where pg_temp.category_name_key(a)=pg_temp.category_name_key(s.name)
  )
 )
);
update category c set parent_id=(select p.id from category p where p.reference_code=s.parent_code and not p.deleted order by p.created_at,p.id limit 1)
from category_seed s where c.reference_code=s.code and s.parent_code is not null;

insert into record_classification(id,created_at,updated_at,invoice_id,status)
select md5('classification:invoice:'||i.id)::uuid,now(),now(),i.id,
 case when exists(select 1 from invoice_category ic where ic.invoice_id=i.id) then 'CLASSIFIED' else 'PENDING' end from invoice i;
insert into record_classification(id,created_at,updated_at,movement_id)
select md5('classification:movement:'||m.id)::uuid,now(),now(),m.id from bank_movement m;
insert into category_assignment(id,created_at,updated_at,classification_id,category_id,origin,evidence)
select md5('legacy-assignment:'||ic.invoice_id||':'||ic.category_id)::uuid,now(),now(),r.id,ic.category_id,'LEGACY','Categorias anteriores preservadas na migração'
from invoice_category ic join record_classification r on r.invoice_id=ic.invoice_id;
-- invoice_category is retained as a migration archive, no longer mapped or written at runtime.
insert into job(id,created_at,updated_at,kind,target_id,target_version,dedupe_key,state,attempts,available_at,payload)
select gen_random_uuid(),now(),now(),case when r.invoice_id is not null then 'CLASSIFY_INVOICE' else 'CLASSIFY_MOVEMENT' end,
 coalesce(r.invoice_id,r.movement_id),0,'categories-bootstrap:'||r.id,'QUEUED',0,now(),'{}'
from record_classification r
where not exists(select 1 from category_assignment a where a.classification_id=r.id)
 and (exists(select 1 from invoice i where i.id=r.invoice_id and not i.deleted)
   or exists(select 1 from bank_movement m where m.id=r.movement_id and not m.deleted));
