select pg_advisory_xact_lock(712690118);

-- Resolve redirects and parents as the category service did when hiding old assignments.
with recursive category_redirects(original_id, current_id) as (
  select id, id from category
  union
  select redirects.original_id, c.merged_into_id
  from category_redirects redirects join category c on c.id = redirects.current_id
  where c.merged_into_id is not null
), canonical_categories(original_id, canonical_id) as (
  select redirects.original_id, redirects.current_id
  from category_redirects redirects join category c on c.id = redirects.current_id
  where c.merged_into_id is null
), category_ancestors(category_id, ancestor_id) as (
  select original_id, canonical_id from canonical_categories
  union
  select ancestors.category_id, parent.canonical_id
  from category_ancestors ancestors
  join category c on c.id = ancestors.ancestor_id
  join canonical_categories parent on parent.original_id = c.parent_id
), invoice_rejections(classification_id, category_id) as (
  select a.classification_id, canonical.canonical_id
  from category_assignment a
  join record_classification r on r.id = a.classification_id
  join canonical_categories canonical on canonical.original_id = a.category_id
  where r.invoice_id is not null and a.rejected
), affected_invoices as (
  update record_classification r
  set reason = case when status = 'PENDING' then reason end,
      applied_fingerprint = null, version = version + 1, updated_at = now()
  where exists (select 1 from invoice_rejections rejected where rejected.classification_id = r.id)
)
delete from category_assignment a
using invoice_rejections rejected, category_ancestors ancestors
where a.classification_id = rejected.classification_id
  and ancestors.category_id = a.category_id
  and ancestors.ancestor_id = rejected.category_id;
