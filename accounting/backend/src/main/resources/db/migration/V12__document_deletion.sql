alter table source_document add column removed_draft_rows text not null default '[]';

create table document_file_deletion (
  storage_key varchar(255) primary key,
  attempts integer not null default 0,
  available_at timestamptz not null default now()
);
