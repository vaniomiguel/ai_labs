alter table ai_execution
  add column job_id uuid,
  add column prompt_tokens integer,
  add column response_tokens integer,
  add column context_tokens integer,
  add column capacity_source varchar(255),
  add column done_reason varchar(255),
  add column context_warning varchar(255),
  add column error text;

create index ai_execution_job_warning_idx on ai_execution (job_id, created_at desc, id desc)
  where context_warning is not null;

create index ai_execution_recent_idx on ai_execution (created_at desc, id desc);
