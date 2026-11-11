-- Schema for the independent accounting application. All financial effects are append-only.

create extension if not exists pg_trgm;

create table ai_execution (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  target_id uuid,
  target_version bigint not null default 0,
  task varchar(255),
  model varchar(255),
  model_digest varchar(255),
  prompt_version varchar(255),
  input_hash varchar(255),
  duration_ms bigint not null default 0,
  outcome varchar(255),
  request text,
  response text
);

create table audit_event (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  actor varchar(255),
  action varchar(255),
  entity_type varchar(255),
  entity_id uuid,
  before_value text,
  after_value text
);

create table bank_account (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  name varchar(255),
  iban varchar(255),
  currency varchar(255),
  deleted boolean not null default false
);

create table bank_movement (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  document_id uuid,
  statement_id uuid,
  account_id uuid,
  bank_reference varchar(255),
  booked_on date,
  value_on date,
  occurred_at time(6),
  description text,
  currency varchar(255),
  amount numeric(19,2),
  direction varchar(255),
  kind varchar(255),
  review_status varchar(255),
  origin varchar(255),
  author varchar(255),
  manually_confirmed boolean not null default false,
  deleted boolean not null default false
);

create table bank_statement (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  document_id uuid,
  account_id uuid,
  period_start date,
  period_end date,
  opening_balance numeric(19,2),
  closing_balance numeric(19,2),
  validated boolean not null default false,
  deleted boolean not null default false
);

create table extraction_attempt (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  document_id uuid,
  engine varchar(255),
  outcome varchar(255),
  result text,
  evidence text
);

create table source_document (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  filename varchar(255),
  media_type varchar(255),
  sha256 varchar(255),
  size bigint not null default 0,
  storage_key varchar(255),
  kind varchar(255),
  source_path text,
  account_id uuid,
  batch_id uuid,
  pages integer not null default 0,
  text text,
  layout text,
  draft text,
  status varchar(255),
  deleted boolean not null default false
);

create table import_batch (
  account_id uuid,
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  filename varchar(255),
  sha256 varchar(255),
  status varchar(255),
  completed_entries bigint not null default 0,
  message text
);

create table legacy_row (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  document_id uuid,
  sheet varchar(255),
  row_number integer not null default 0,
  year integer not null default 0,
  month integer not null default 0,
  date date,
  description text,
  received numeric(19,2),
  payment numeric(19,2),
  balance numeric(19,2),
  cells text,
  invoice_id uuid,
  status varchar(255)
);

create table category (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  name varchar(255),
  aliases text,
  deleted boolean not null default false
);

create table invoice (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  issued_on date,
  due_on date,
  number varchar(255),
  supplier varchar(255),
  supplier_tax_id varchar(255),
  description text,
  currency varchar(255),
  total numeric(19,2),
  net numeric(19,2),
  tax numeric(19,2),
  discount numeric(19,2),
  type varchar(255),
  related_invoice_id uuid,
  document_id uuid,
  review_status varchar(255),
  origin varchar(255),
  author varchar(255),
  manually_confirmed boolean not null default false,
  deleted boolean not null default false
);

create table invoice_line (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  invoice_id uuid,
  position integer not null default 0,
  description text,
  quantity numeric(19,6),
  unit_price numeric(19,6),
  net numeric(19,2),
  tax numeric(19,2),
  discount numeric(19,2),
  total numeric(19,2)
);

create table job (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  kind varchar(255),
  target_id uuid,
  target_version bigint not null default 0,
  dedupe_key varchar(255),
  state varchar(255),
  attempts integer not null default 0,
  available_at timestamptz,
  leased_until timestamptz,
  lease_token uuid,
  payload text,
  error text
);

create table ledger_entry (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  source_type varchar(255),
  source_id uuid,
  source_version bigint not null default 0,
  effective_on date,
  amount numeric(19,2),
  reverses_id uuid,
  explanation text
);

create table allocation (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  invoice_id uuid,
  movement_id uuid,
  reverses_id uuid,
  amount numeric(19,2),
  origin varchar(255),
  author varchar(255),
  evidence text,
  active boolean not null default false
);

create table alert (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  document_id uuid,
  entity_type varchar(255),
  entity_id uuid,
  code varchar(255),
  message text,
  status varchar(255),
  proposal text,
  resolved_by varchar(255),
  resolved_at timestamptz
);

create table app_setting (
  id uuid primary key,
  version bigint not null default 0,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  name varchar(255),
  value text
);


create table invoice_category (invoice_id uuid not null references invoice(id), category_id uuid not null references category(id), primary key(invoice_id,category_id));
create unique index category_name_active on category(lower(name)) where not deleted;
create unique index source_document_hash_kind on source_document(sha256,kind,coalesce(account_id,'00000000-0000-0000-0000-000000000000'::uuid));
create unique index import_batch_hash on import_batch(sha256,account_id);
create unique index app_setting_name on app_setting(name);
create unique index job_dedupe on job(dedupe_key);
create index job_pending on job(state,available_at,leased_until);
create index invoices_date on invoice(issued_on) where not deleted;
create index movements_date on bank_movement(booked_on) where not deleted;
create index allocations_invoice on allocation(invoice_id) where active;
create index allocations_movement on allocation(movement_id) where active;
create index ledger_period on ledger_entry(effective_on);
create index ledger_source on ledger_entry(source_type,source_id);
create unique index ledger_reverse_once on ledger_entry(reverses_id) where reverses_id is not null;
create index alerts_open on alert(status,document_id);
create index invoice_search on invoice using gin(to_tsvector('portuguese',coalesce(supplier,'') || ' ' || coalesce(description,'') || ' ' || coalesce(number,'')));
create index document_search on source_document using gin(to_tsvector('portuguese',text));
create index movement_description_search on bank_movement using gin(description gin_trgm_ops);
create unique index legacy_source_row on legacy_row(document_id,sheet,row_number);
alter table invoice add constraint invoice_total_positive check(review_status = 'OPEN' or total is null or total >= 0);
alter table bank_movement add constraint movement_amount_positive check(review_status = 'OPEN' or amount is null or amount > 0);
alter table allocation add constraint allocation_positive check(amount > 0), add foreign key(invoice_id) references invoice(id), add foreign key(movement_id) references bank_movement(id), add foreign key(reverses_id) references allocation(id);
alter table invoice_line add foreign key(invoice_id) references invoice(id);
alter table bank_movement add foreign key(account_id) references bank_account(id), add foreign key(statement_id) references bank_statement(id);
alter table bank_statement add foreign key(account_id) references bank_account(id);
create table app_user (username varchar(100) primary key, password_hash varchar(255) not null, created_at timestamptz not null default now());
