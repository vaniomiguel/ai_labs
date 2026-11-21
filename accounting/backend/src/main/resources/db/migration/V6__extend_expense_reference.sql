select pg_advisory_xact_lock(712690118);

create temporary table expense_reference_seed(
 code text primary key, name text not null, reference_name text not null, parent_code text
) on commit drop;

insert into expense_reference_seed values
 ('62', 'Fornecimentos e serviços externos', 'Fornecimentos e serviços externos', null),
 ('621', 'Subcontratos', 'Subcontratos', '62'),
 ('6228', 'Outros serviços especializados', 'Outros', '622'),
 ('6234', 'Artigos para oferta', 'Artigos para oferta', '623'),
 ('6238', 'Outros materiais', 'Outros', '623'),
 ('6248', 'Outros gastos com energia e fluidos', 'Outros', '624'),
 ('6258', 'Outras despesas de deslocação e transporte', 'Outros', '625'),
 ('6268', 'Outros serviços diversos', 'Outros serviços', '626'),
 ('631', 'Remunerações dos órgãos sociais', 'Remunerações dos órgãos sociais', '63'),
 ('632', 'Remunerações do pessoal', 'Remunerações do pessoal', '63'),
 ('633', 'Benefícios pós-emprego', 'Benefícios pós-emprego', '63'),
 ('6331', 'Prémios para pensões', 'Prémios para pensões', '633'),
 ('6332', 'Outros benefícios pós-emprego', 'Outros benefícios', '633'),
 ('634', 'Indemnizações', 'Indemnizações', '63'),
 ('635', 'Encargos sobre remunerações', 'Encargos sobre remunerações', '63'),
 ('636', 'Seguros de acidentes no trabalho e doenças profissionais', 'Seguros de acidentes no trabalho e doenças profissionais', '63'),
 ('637', 'Gastos de ação social', 'Gastos de ação social', '63'),
 ('638', 'Outros gastos com o pessoal', 'Outros gastos com o pessoal', '63'),
 ('6811', 'Impostos diretos', 'Impostos diretos', '681'),
 ('6812', 'Impostos indiretos', 'Impostos indiretos', '681'),
 ('6911', 'Juros de financiamentos obtidos', 'Juros de financiamentos obtidos', '691'),
 ('6918', 'Outros juros', 'Outros juros', '691'),
 ('692', 'Diferenças de câmbio desfavoráveis', 'Diferenças de câmbio desfavoráveis', '69'),
 ('6921', 'Diferenças de câmbio em financiamentos', 'Relativas a financiamentos obtidos', '692'),
 ('6928', 'Outras diferenças de câmbio desfavoráveis', 'Outras', '692'),
 ('698', 'Outros gastos de financiamento', 'Outros gastos de financiamento', '69'),
 ('6981', 'Outros encargos de financiamentos obtidos', 'Relativos a financiamentos obtidos', '698'),
 ('6988', 'Outros encargos financeiros', 'Outros', '698');

-- Match display names and aliases using the same accent and punctuation rules as the V3 seed.
create function pg_temp.expense_reference_name_key(value text) returns text language sql immutable as $$
 select trim(regexp_replace(translate(lower(coalesce(value,'')),
 'áàâãäéèêëíìîïóòôõöúùûüç','aaaaaeeeeiiiiooooouuuuc'),'[^a-z0-9]+',' ','g'))
$$;

do $$
declare
 item record;
 label text;
 suffix integer;
 parent uuid;
begin
 for item in select * from expense_reference_seed order by length(code), code loop
   if exists (select 1 from category where reference_code = item.code) then
     continue;
   end if;
   label := item.name;
   suffix := 0;
   while exists (
     select 1 from category c
     where pg_temp.expense_reference_name_key(c.name) = pg_temp.expense_reference_name_key(label)
       or exists (
         select 1 from jsonb_array_elements_text(
           case when jsonb_typeof(c.aliases::jsonb) = 'array' then c.aliases::jsonb else '[]'::jsonb end
         ) as alias
         where pg_temp.expense_reference_name_key(alias) = pg_temp.expense_reference_name_key(label)
       )
   ) loop
     suffix := suffix + 1;
     label := item.name || ' · SNC ' || item.code || case when suffix > 1 then ' (' || suffix || ')' else '' end;
   end loop;
   select id into parent from category where reference_code = item.parent_code
     order by deleted, created_at, id limit 1;
   insert into category(id, created_at, updated_at, name, aliases, origin, protected_category,
       reference_code, reference_name, reference_version, reference_source, parent_id)
   values(md5('category:snc:' || item.code)::uuid, now(), now(), label, '[]', 'REFERENCE', true,
       item.code, item.reference_name, 'OCC-SNC-2019-02-EXPENSES-2', 'https://www.occ.pt/fotos/editor2/taxonomiasplanocontas_fev2019.pdf', parent);
 end loop;
end
$$;

-- V3 gave these families a root placement. Move only the original, untouched seed rows.
update category c set parent_id = (select id from category where reference_code = '62' order by deleted, created_at, id limit 1),
 version = version + 1, updated_at = now()
where c.reference_code in ('622', '623', '624', '625', '626')
 and c.id = md5('category:snc:' || c.reference_code)::uuid
 and c.origin = 'REFERENCE' and c.version = 0 and c.parent_id is null and not c.deleted;

update category_catalog set revision = revision + 1, version = version + 1, updated_at = now();
