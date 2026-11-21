# Conta

Aplicação independente para despesas, documentos, reconciliação bancária e saldo do gestor. Os projetos AI Labs existentes não são alterados nem carregados por esta aplicação.

## Início rápido local

### Java local e serviços no Docker

Requisitos: Docker Desktop aberto, Java 25, Maven e Python 3. O frontend é compilado no Docker e servido pelo Java; não é necessário Node no computador.

Preparar `accounting/.env` a partir de `.env.example`, definindo `ADMIN_PASSWORD` e `DATABASE_PASSWORD`. Depois, a partir da raiz do repositório:

```sh
bash accounting/scripts/run-hybrid.sh
```

Abrir [http://localhost:8081](http://localhost:8081) e entrar com `gestor` e a palavra-passe inicial definida no `.env`. Em desenvolvimento, as palavras-passe podem ser curtas, por exemplo `ADMIN_PASSWORD=dev` e `DATABASE_PASSWORD=dev`. Fora do perfil `dev`, a palavra-passe do utilizador exige pelo menos 12 caracteres. Mudar o `.env` não altera as credenciais de uma base de dados já criada. O Ollama continua no computador em `localhost:11434`. `HYBRID_OLLAMA_URL` permite mudar esse endereço.

Este modo usa uma instalação de desenvolvimento própria: PostgreSQL em `localhost:15432`, OCR em `localhost:18090` e originais em `accounting/data/hybrid/documents`. Os dados da instalação totalmente em Docker não são migrados automaticamente. Se essa aplicação estiver aberta, pará-la com `docker compose -f accounting/compose.yml stop app` antes de iniciar o Java na mesma porta.

Para parar o Java, usar **Ctrl+C**. Para parar os serviços:

```sh
docker compose -f accounting/compose.hybrid.yml stop
```

### Java no IDE e restantes serviços no Docker

1. Abrir Docker Desktop e preparar `accounting/.env` com `ADMIN_PASSWORD=dev` e `DATABASE_PASSWORD=dev` (numa base de dados existente, manter a palavra-passe original).
2. No terminal, executar:

   ```sh
   cd /Users/vanioferreira/Workplace/ai_labs/accounting
   docker compose -f compose.hybrid.yml up -d --build postgres documents
   docker compose -f compose.hybrid.yml --profile ide up -d frontend
   ```

   A primeira inicialização da interface instala as dependências. Consultar o progresso com `docker compose -f compose.hybrid.yml logs -f frontend`.
3. Abrir a aplicação Ollama no computador. Se o modelo ainda não estiver instalado: `ollama pull qwen3.5:9b`.
4. No IDE, importar `accounting/backend/pom.xml` como projeto Maven, selecionar JDK 25 e criar uma configuração Java/Spring Boot com classe principal `pt.conta.AccountingApplication`. Usar `/Users/vanioferreira/Workplace/ai_labs/accounting` como diretório de trabalho e estas variáveis de ambiente:

   ```dotenv
   SPRING_PROFILES_ACTIVE=dev
   ADMIN_USERNAME=gestor
   ADMIN_PASSWORD=dev
   DATABASE_USER=accounting
   DATABASE_PASSWORD=dev
   DATABASE_URL=jdbc:postgresql://localhost:15432/accounting
   DOCUMENT_WORKER_URL=http://localhost:18090
   OLLAMA_URL=http://localhost:11434
   DOCUMENT_STORAGE=./data/hybrid/documents
   BIND_ADDRESS=0.0.0.0
   PORT=8081
   ```

   O Java iniciado pelo IDE não lê automaticamente o `.env`. Configurar aí as mesmas palavras-passe. O endereço de escuta permite que a interface no Docker contacte o Java no computador.
5. Carregar em **Run** ou **Debug** e abrir [http://localhost:4200](http://localhost:4200). Entrar com `gestor` e a palavra-passe configurada.

Parar o Java com **Stop** no IDE. Parar os restantes serviços com `docker compose -f compose.hybrid.yml --profile ide stop`. Não arrancar simultaneamente a aplicação Java da composição normal ou do script na porta 8081.

### Java no IDE e interface local pela linha de comandos

Usar a configuração Java do IDE acima. Apenas PostgreSQL e OCR precisam do Docker:

```sh
cd /Users/vanioferreira/Workplace/ai_labs/accounting
docker compose -f compose.hybrid.yml up -d --build postgres documents
```

Se a interface Docker anterior estiver em execução, pará-la antes de usar a porta 4200:

```sh
docker compose -f compose.hybrid.yml --profile ide stop frontend
```

Com Node 24.15+ e npm instalados, abrir outro terminal:

```sh
cd /Users/vanioferreira/Workplace/ai_labs/accounting/frontend
npm ci
npx ng serve --host 127.0.0.1 --port 4200 --proxy-config proxy.conf.json
```

`npm ci` só precisa de ser repetido quando mudarem as dependências. O comando `npx ng serve` mantém a interface em execução e atualiza-a quando se altera o código. Usa o Angular já instalado no projeto. O proxy encaminha os pedidos da API para o Java em `localhost:8081`.

Arrancar o Java com **Run/Debug** no IDE, manter o Ollama aberto e aceder a [http://localhost:4200](http://localhost:4200). A configuração Java pode usar `BIND_ADDRESS=127.0.0.1`, pois a interface já corre no computador. Parar a interface com **Ctrl+C**, o Java com **Stop** e os serviços com `docker compose -f compose.hybrid.yml stop postgres documents` a partir da pasta `accounting`.

### Tudo local, sem Docker

Requisitos: Java 25, Maven, Node 24.15+ e npm. O lançador inclui PostgreSQL 18 real para desenvolvimento; os dados ficam em `accounting/data/`, sem Docker.

```sh
bash accounting/scripts/run-local.sh
```

Abrir [http://localhost:8081](http://localhost:8081). Em desenvolvimento, o utilizador inicial é `gestor` e a palavra-passe é `alterar-em-desenvolvimento`. Para definir a tua credencial inicial, exportar `ADMIN_PASSWORD` antes do primeiro arranque. A palavra-passe é guardada como hash; mudar a variável não substitui uma conta existente.

Começar por **Categorias e contas → Adicionar conta**. Depois importar faturas ou extratos em **Extrações**, ou criar registos manualmente. Os documentos que precisem de revisão ficam fora dos cálculos. Uma fatura validada entra no saldo mesmo enquanto está por reconciliar.

O lançador usa dependências de teste apenas em desenvolvimento. A imagem de produção contém o JAR normal, sem PostgreSQL embutido nem testes.

### Serviço documental e Ollama

O PDFBox trata PDFs com texto nativo sem serviço adicional. Para OCR e tabelas:

```sh
cd accounting/document-worker
uv sync --python 3.12
uv run uvicorn app:app --host 127.0.0.1 --port 8090
```

Instalar Tesseract no sistema com os idiomas `por` e `eng`. O Docling descarrega os seus modelos na primeira utilização; para trabalhar sem rede, preparar previamente a cache e, opcionalmente, definir `DOCLING_ARTIFACTS_PATH` com um diretório de modelos completo. O serviço executa os modelos de layout/OCR em CPU e não usa serviços remotos.

Ollama corre diretamente no sistema, por omissão em `http://localhost:11434`. Instalar inicialmente `qwen3.5:9b` e selecionar os modelos em **Assistente de IA**. Sem Ollama, o processamento por regras continua e as revisões ficam a aguardar. A aplicação não descarrega modelos nem usa aliases cloud automaticamente.

Deixar `OLLAMA_URL` por definir quando se usam os endereços habituais: o Java local escolhe `localhost` e a composição Docker escolhe `host.docker.internal`. Se o IDE carregar o `.env`, não usar aí o endereço Docker para executar Java no computador. Depois de alterar o ambiente, reiniciar o Java. O Assistente mostra o endereço usado e o motivo de uma falha de ligação; as tarefas em espera retomam automaticamente, sem reimportar os documentos.

O contador **tarefas pendentes** abre **Processamento**, também disponível na navegação. Cada tarefa corresponde a uma operação de segundo plano: importar um extrato com dois movimentos pode gerar duas revisões de movimentos e uma revisão do documento. O ecrã mostra a fila global, permite filtrar os estados e consultar o histórico e o detalhe de cada tarefa, incluindo o registo associado e a próxima tentativa. A classificação contabilística de um movimento é independente do estado da sua revisão de IA.

### Desenvolvimento separado

Com PostgreSQL existente, definir `DATABASE_URL`, `DATABASE_USER` e `DATABASE_PASSWORD` e executar `mvn -f accounting/backend/pom.xml spring-boot:run`. A interface pode correr em `accounting/frontend` com `npm start`, em [http://localhost:4200](http://localhost:4200), usando o proxy local.

## Docker

```sh
cd accounting
cp .env.example .env
# Definir as duas palavras-passe no .env.
docker compose up --build -d
```

Acesso local em [http://localhost:8081](http://localhost:8081). A composição inicia PostgreSQL 18, Java/interface e o serviço documental. O Ollama permanece no anfitrião; o seu endereço é configurado em `OLLAMA_URL`. Em Linux, garantir que a interface do Ollama é acessível a partir da rede Docker; o endereço de loopback do anfitrião não é o loopback do contentor.

Para publicar através de HTTPS, definir `SPRING_PROFILES_ACTIVE=prod` e colocar o serviço atrás de um proxy HTTPS. O perfil `prod` exige cookies seguros; o perfil `dev` permite a instalação HTTP local. A base de dados e o serviço documental não expõem portas na composição normal. `compose.dev.yml` permite expô-las apenas no loopback quando necessário.

Os volumes PostgreSQL, documentos e modelos são persistentes. O formato de volume usado é o do PostgreSQL 18. Não executar `docker compose down -v` sobre dados a preservar.

## Regras financeiras

- Faturas validadas aumentam o saldo na data de emissão; notas de crédito têm efeito inverso.
- Todos os movimentos validados contam na data bancária: débitos reduzem o saldo e créditos aumentam-no, mesmo sem fatura correspondente. As associações não lançam novamente esses valores.
- Cada janeiro começa em zero. Os meses seguintes recebem o saldo do anterior dentro do mesmo ano.
- Fatura de dezembro de 100 € e pagamento em fevereiro de 60 €: +100 € no ano antigo, −60 € no novo, 40 € por reconciliar na fatura.
- Uma fatura de 100 € com débito de 60 €, 100 € ou 120 € deixa, respetivamente, +40 €, 0 € ou −20 €. Sem movimento, presume-se que o gestor suportou a fatura; sem fatura, o movimento conta integralmente pelo seu sinal. As classificações não alteram estes sinais.
- As associações aceitam pagamentos parciais e agrupados. Uma referência inequívoca permite valores diferentes, incluindo excesso de pagamento; igualdade de valores isolada não confirma uma correspondência. Uma secção crítica em PostgreSQL impede atribuir duas vezes o mesmo valor.
- Dentro do mesmo ano consideram-se todos os meses. Entre anos usa-se a distância de meses de calendário, três por omissão: dezembro–março permitido, dezembro–abril excluído.
- Categorias são etiquetas, não repartições monetárias. Somar categorias diferentes pode contar a mesma fatura mais de uma vez.

Os efeitos financeiros são lançamentos imutáveis com anulações datadas. A vista **Saldos** discrimina entradas, reduções e os lançamentos de cada mês, com acesso aos originais disponíveis. Eliminar um registo não elimina o documento nem o histórico. O saldo aplica as presunções acima aos dados disponíveis; novas importações voltam a procurar correspondências sem duplicar os efeitos financeiros.

A **Reconciliação** mostra faturas e movimentos sem correspondência e uma linha expansível por grupo associado. Datas e valores diferentes têm indicadores próprios. Nos grupos que atravessam períodos, o saldo global e o impacto no período filtrado são apresentados separadamente; os saldos anuais continuam a respeitar as datas de cada registo.

**Excluir do saldo** conserva o registo visível e o seu histórico, mas retira-o dos cálculos e da procura de correspondências. Numa associação, escolhem-se as faturas, os movimentos ou ambos; os restantes registos voltam a reconciliar. **Voltar a incluir** verifica os dados guardados e procura correspondências atuais, sem repetir a extração do original nem aprovar dados por validar. As republicações conservam a identidade dos registos, mesmo que a ordem das linhas mude; se uma extração alterada não conseguir identificar um registo excluído, pede-se revisão em vez de transferir o marcador para outra linha. A migração V2 atualiza automaticamente os lançamentos antigos no arranque, preservando-os com anulações datadas.

## Extração, revisão e histórico

As vistas **Reconciliação**, **Faturas** e **Movimentos** incluem registos **Por validar**. Os pendentes sem data aparecem primeiro, em qualquer ano, mês ou intervalo selecionado; os registos com data respeitam o período e os restantes filtros continuam a aplicar-se. O nome do original identifica entradas sem fornecedor ou descrição e pode ser pesquisado.

Os montantes extraídos dos pendentes são provisórios. Até à validação, não há montante disponível para associar nem impacto no saldo: são apresentados como **—**. Os registos entram na paginação e na contagem de linhas, mas ficam fora de correspondências, sugestões e totais financeiros. O estado de validação é independente da exclusão do saldo; ambos os marcadores podem aparecer no mesmo registo.

**Rever e validar** abre o editor do item escolhido, com o original ao lado e os dados que precisam de correção. Guardar rascunho conserva apenas esse item por validar. **Validar e guardar** permite-lhe participar nos cálculos. Cada fatura ou movimento é validado individualmente. Uma questão partilhada ou sem item identificado tem um alerta no documento, que requer resolução na revisão do original.

As edições individuais sincronizam apenas a entrada correspondente no rascunho original, conservando as restantes entradas e metadados. Os clientes atuais enviam a versão do registo e `documentVersion`; uma edição concorrente ou uma identidade antiga ambígua devolve conflito, sem substituir outro item. Os clientes antigos podem omitir `documentVersion`. Depois de validar, as listas e os saldos atualizam-se; se a data corrigida pertencer a outro período, é mostrada uma confirmação sem alterar os filtros selecionados.

Cada fatura ou movimento tem até um alerta aberto com todas as questões identificadas. O editor de alertas permite editar os campos e experimentar sugestões da IA no rascunho. **Descartar alterações** fecha o editor sem guardar. **Guardar sem resolver** conserva o rascunho por validar. **Validar e resolver** publica os valores e encerra o alerta. Depois da validação manual, o registo fica protegido de novos alertas. Podem ser validadas novas edições pelo editor normal, com os saldos e as reconciliações atualizados.

As importações são assíncronas e persistentes. Cada documento é arquivado com SHA-256. Os estados de processamento, revisão e reconciliação são independentes.

Os parsers genéricos são conservadores: totais ambíguos, artigos não reconhecidos ou movimentos sem data, descrição, montante ou direção exigem revisão. Não existem formatos universais de PDF bancário; a leitura pode precisar de IA ou correção manual. O original pode ser consultado lado a lado com os campos e cada linha extraída.

O Excel histórico é uma referência, nunca uma fonte de pagamentos bancários inventados. As linhas e fórmulas originais ficam guardadas, os totais não viram despesas e diferenças com documentos geram alertas. A importação ZIP retoma por entrada e volta a usar documentos idênticos. Coincidências bancárias entre extratos diferentes exigem confirmação para não descartar transações legítimas iguais.

O mapeamento de Excel pode ser guardado em **Histórico**; reprocessar depois o documento. As colunas são índices a partir de zero no contrato da API. As folhas, células, fórmulas e resultados estão disponíveis na revisão.

## IA e auditoria

### Correção por IA no diálogo de revisão

**Pedir tentativa de correção à IA** mostra imediatamente um indicador dentro do diálogo. Acompanha os estados **Em fila**, **A analisar**, **A aguardar modelo** e **Falhou**, e apresenta automaticamente a proposta concluída. É possível fechar e reabrir o documento para recuperar a tentativa. Os eventos de progresso atualizam o estado; consultas de segurança continuam a cada dois segundos durante processamento e dez segundos enquanto aguarda o modelo.

A comparação distingue **Complementos** de **Sugestões de alteração**, por comprovativo ou movimento e campo, com evidência quando disponível. Os valores humanos mantêm-se por omissão: **Manter o meu valor** pode ser trocado por **Aceitar valor da IA** em cada conflito e a escolha pode ser revertida antes de guardar. As edições locais conservam-se enquanto chega o resultado e prevalecem sobre sugestões ainda não aceites.

**Aplicar ao rascunho** guarda complementos e escolhas dos itens selecionados, mesmo quando faltam dados obrigatórios. **Aplicar proposta e validar** verifica o resultado combinado no servidor e explica os impedimentos junto ao botão. Irmãos não selecionados conservam os seus dados e validações. Alterações guardadas num rascunho de um registo já publicado não mudam os valores financeiros publicados até validação. Resultados incompletos, sem alterações e desatualizados têm mensagens próprias. Pedidos repetidos reutilizam a tentativa ativa; uma nova análise depois de terminar faz uma nova chamada ao modelo.

A proteção é aplicada no servidor em extrações, reprocessamentos e correções. Cada campo guarda origem, autor, instante e referência da proposta quando aplicável. Uma edição protege o campo alterado, incluindo uma limpeza explícita; validar protege todos os valores preenchidos, sem bloquear campos opcionais nunca preenchidos. Uma sugestão aceite passa a ser uma decisão humana protegida. Os artigos são uma coleção protegida quando editada ou validada. A IA não remove nem reagrupa automaticamente entradas existentes. As categorias e exclusões mantêm as suas proteções próprias.

A migração **V4** acrescenta a proveniência sem alterar montantes, originais ou lançamentos. Recupera decisões humanas e limpezas reconhecíveis no histórico; edições antigas do original com atribuição ambígua ficam protegidas conservadoramente e podem exigir aceitação explícita de um complemento. O contrato expõe `fieldProvenance` apenas para leitura; `/alerts/{id}/preview` verifica escolhas sem publicar e `/alerts/{id}/apply` aceita apenas sugestões guardadas, com controlo de versão e identidade.

Cada correção recebe o texto integral extraído, o rascunho e o motivo do alerta. Modelos com capacidade visual recebem também imagens de todas as páginas do PDF, obtidas diretamente do original mesmo que a extração não tenha metadados de páginas. O histórico guarda modelo, versão documental, páginas e evidências; as imagens continuam a ser recuperáveis através do original arquivado.

O limite inicial da aplicação é de 50 000 caracteres para o pedido completo, incluindo prompt e esquema, e 16 páginas visuais. `REPAIR_MAX_INPUT_CHARACTERS`, `REPAIR_MAX_IMAGE_PAGES` e `AI_CONTEXT_TOKENS` permitem ajustar a capacidade, sendo 16 384 tokens o contexto pedido por omissão ao Ollama. Uma entrada demasiado grande falha com explicação, sem enviar um subconjunto do texto ou das páginas nem repetir automaticamente o pedido inviável. Nas correções, o transporte envia `truncate=false` e `shift=false`, opções do [contrato oficial do Ollama](https://github.com/ollama/ollama/blob/main/api/types.go), para recusar a perda de contexto.

**Processamento** e **Assistente de IA → Histórico de IA** mostram avisos a partir de 80% de ocupação, quando o limite é atingido ou quando o modelo recusa ou interrompe um pedido por limite. O histórico mostra os tokens de entrada e de resposta comunicados pelo Ollama após cada chamada. A capacidade usada é o menor valor entre o contexto configurado e o contexto do modelo em execução, quando disponível. Cada parte e cada repetição é medida separadamente. Quando faltam contagens, a percentagem fica indisponível.

### Categorias de despesas

Faturas e movimentos aceitam várias etiquetas, organizadas em famílias. A IA usa descrições, artigos, fornecedores, sinónimos e exemplos anteriores; os movimentos associados herdam a união das etiquetas das suas faturas. Sem etiquetas nas faturas, a classificação bancária permanece provisória. Desassociar ou alterar as faturas atualiza a herança. Registos excluídos do saldo também são classificados.

O catálogo usa a referência local `backend/src/main/resources/category-reference.json`, com 60 referências da OCC/SNC. Inclui as subcontas nomeadas de fornecimentos e serviços externos (62) e contas selecionadas de pessoal, impostos e financiamento. São etiquetas de organização, sem repartir valores nem produzir enquadramento fiscal. A **Natureza do movimento** (pagamento, reembolso, adiantamento…) é um campo separado.

As listagens mostram duas etiquetas e permitem expandir as restantes. **Editar categorias** permite selecionar várias, criar uma categoria e consultar a origem e a justificação. Retirar uma etiqueta guarda uma rejeição: a IA e a herança não a repõem até **Permitir IA nas removidas**. Estas decisões têm uma versão independente das edições financeiras.

Em **Categorias e contas**, é possível renomear, mudar de família, editar sinónimos, fundir, arquivar e proteger categorias. As alterações manuais ficam protegidas por omissão; podem ser desbloqueadas individualmente ou através de **Permitir evolução automática**. As famílias de referência permanecem protegidas perante a IA. Arquivar impede novas atribuições e conserva as etiquetas históricas; só categorias sem utilizações nem filhas podem ser eliminadas definitivamente.

A evolução automática generaliza variantes e funde duplicados através de identidades preservadas e encaminhamentos: por exemplo, «Cabos USB-C» passa a mostrar «Cabos USB» no histórico quando surge USB-A. O histórico explica as alterações e permite desfazê-las. O desfazer preserva utilizações da categoria geral e decisões manuais posteriores, protegendo os conceitos restaurados contra repetição automática. Alterações estruturais posteriores incompatíveis devolvem conflito de versão.

A classificação corre na fila persistente depois de criações, importações e alterações relevantes. No editor da fatura, incluindo em **Rever e validar** e na revisão de um alerta, **Rever categorias com IA** atualiza as etiquetas atribuídas pela IA. Para faturas sem etiquetas, **Classificar com IA** está disponível nesse editor, na lista de faturas e em **Editar categorias**. O pedido usa os dados guardados e preserva as decisões manuais. O editor mostra o progresso, as etiquetas e o motivo da classificação sem interromper a edição. Uma tarefa já ativa é reutilizada. A ação usa `POST /api/v1/invoices/{id}/categories/classify` e recebe a versão independente da classificação.

O modelo configurado em `model:categories` usa `categories.md` para classificar movimentos e faturas e `category-catalog-review.md` para a ação **Rever categorias** e para a manutenção automática do catálogo. Ambos os ficheiros admitem substituições em `PROMPT_DIRECTORY` e usam o mesmo esquema `categories.schema.json`. A mensagem do utilizador inclui os dados guardados, as escolhas humanas, o catálogo, os exemplos e o esquema. A classificação usa apenas texto e pode incluir até 16 000 caracteres do OCR de uma fatura individual. Os exemplos são opcionais no limite de 90 000 caracteres do pedido completo. Cada etiqueta requer uma citação literal do registo. Exemplos anteriores podem fundamentar operações do catálogo. Referências bancárias genéricas exigem revisão.

A migração V3 conserva identificadores, nomes, sinónimos e associações existentes, marcando as categorias antigas como protegidas por terem origem indeterminada. Introduz a referência sem duplicar nomes ou sinónimos, encaminha a normalização antiga e enfileira registos antigos sem categorias. Aplica-se no próximo arranque do backend. Não altera lançamentos, saldos, exclusões ou regras de reconciliação.

A migração V6 acrescenta as 28 referências em falta e agrupa sob a conta 62 as famílias originais que ainda não foram editadas. Preserva identificadores, nomes, associações e decisões existentes. As alterações de prompts aplicam-se a classificações futuras e a pedidos de nova classificação.

As interfaces `AiProvider` e `DocumentConverter` isolam o domínio dos fornecedores. O adaptador implementado usa a API nativa Ollama, incluindo imagens e esquemas. Outro fornecedor implementa essas interfaces sem alterar os cálculos.

As prompts e os esquemas estão centralizados em `backend/src/main/resources/prompts/`. `PROMPT_DIRECTORY` permite substituir os ficheiros Markdown sem recompilar. Cada execução guarda o hash da prompt, digest do modelo, entrada, resposta, duração e validação do esquema. Mudar a configuração aplica-se às próximas tarefas; não reprocessa silenciosamente o histórico.

A extração automática de faturas e a análise de faturas pedida na revisão enviam apenas texto, mesmo quando o modelo suporta visão. `extract-invoice.md` define a mensagem de sistema da extração automática e `repair-invoice.md` define a revisão de faturas com campos em falta e dados já preenchidos. `extract-invoice-fields.md` define os campos pedidos na mensagem do utilizador em ambos os percursos. Os três recursos têm identificadores próprios em `PromptCatalog` e podem ser substituídos em UTF-8 pelos mesmos nomes em `PROMPT_DIRECTORY`, com fallback individual para os recursos incluídos. As definições de campos não têm esquema próprio: mantém-se `extract-invoice.schema.json`.

A **Descrição** da fatura é uma frase curta que identifica o produto ou serviço e fica vazia quando o OCR não permite identificá-lo. O texto integral extraído é guardado separadamente. Ao substituir `extract-invoice-fields.md` ou `extract-invoice.md` em `PROMPT_DIRECTORY`, conserva a regra da descrição curta. Em `repair-invoice.md`, conserva a regra que permite propor a substituição de texto OCR copiado por uma descrição com evidência, apenas para valores automáticos cuja correção é permitida.

As faturas usam apenas `issuedOn` (**Data de emissão**); o campo **Vencimento** (`dueOn`) foi removido do formulário, da API e da extração. A migração **V5**, aplicada no arranque, elimina a coluna e os valores de vencimento dos rascunhos atuais, da proveniência e das propostas de correção abertas. Preserva as datas de emissão, os originais e o histórico de auditoria e execuções; nunca transforma um vencimento numa data de emissão. Escritas antigas com `dueOn` ignoram esse campo.

As duas mensagens de sistema acrescentam a mesma orientação para escolher a data de emissão: analisar primeiro o texto em redor das datas candidatas; se a dúvida persistir e todas pertencerem ao mesmo mês e ano, escolher a mais antiga. Datas identificadas como vencimento, serviço, viagem ou impressão continuam excluídas. Nos restantes casos incertos, manter `null` e indicar a dúvida em `issues`. Substituições externas de `extract-invoice.md` e `repair-invoice.md` devem incluir esta orientação; `extract-invoice-fields.md` deve pedir apenas a data de emissão.

A extração automática envia exatamente `fields` e `sourceText`. A análise pedida na revisão acrescenta o rascunho guardado, a proveniência dos campos, o alerta e os metadados da tentativa, conservando a confirmação explícita da proposta. O texto integral guardado pelo conversor segue numa única chamada, sem fragmentação, imagens ou leitura do PDF pelo percurso de IA. Texto vazio exige revisão da extração; conteúdo demasiado extenso falha sem ser cortado, incluindo instruções, entrada e esquema no limite de 50 000 caracteres da extração automática ou no limite configurado por `REPAIR_MAX_INPUT_CHARACTERS` para correções. O limite visual de páginas não se aplica a faturas. `categories` é sempre pedido como `[]`; a classificação continua a cargo do respetivo worker.

Na revisão de faturas, `INVOICE_REPAIR_SYSTEM_INSTRUCTIONS` identifica `repair-invoice.md`, substituível pelo mesmo nome em `PROMPT_DIRECTORY`, sem esquema próprio. Mantêm-se o modelo de extração de faturas e o esquema de resposta existente. A mensagem do utilizador acrescenta `fieldsToExtract` (campos em falta, prioritariamente os obrigatórios para validação), `filledFields` (valores guardados, origem, autoridade e possibilidade de proposta de correção), `responseSchema` (o mesmo esquema enviado ao fornecedor) e `responseTemplate` (estrutura JSON completa com os valores conhecidos e lacunas). Os IDs de persistência e os controlos internos não entram no modelo de resposta. Estas instruções, o esquema e o modelo JSON contam para o limite de contexto existente.

Os valores humanos e os protegidos pela política de proveniência são autoritativos; uma divergência explícita no OCR é descrita em `issues`, preservando o valor humano. Valores de origem desconhecida são conservados sem serem classificados como humanos. Apenas valores explicitamente automáticos e não protegidos admitem propostas de correção com evidência. As proteções de artigos aplicam-se aos seus campos e as referências indexadas distinguem artigos e comprovativos independentes. A análise usa os valores persistidos; é necessário guardar as edições do formulário antes de pedir a análise. A resposta continua a ser uma proposta completa, sujeita à confirmação habitual.

As instruções incluídas são escritas em inglês. Nomes do catálogo, exemplos literais de documentos portugueses, identificadores e campos JSON mantêm a forma original para conservar as correspondências e a evidência. A tradução altera os hashes das próximas execuções; os resultados históricos não são reescritos.

As instruções complementares também usam esse catálogo, com identificadores constantes em `PromptCatalog`: `repair-analysis-instructions.md` orienta a análise integral de correções dos restantes tipos de documento; `human-field-authority-instructions.md` protege os dados humanos nas restantes extrações e revisões; `category-validation-retry-instructions.md` orienta a nova tentativa após uma rejeição de categorias. Na extração automática e na revisão de faturas, essas regras já integram, respetivamente, `extract-invoice.md` e `repair-invoice.md`. Podem ser substituídas individualmente por ficheiros UTF-8 com os mesmos nomes em `PROMPT_DIRECTORY`; os ficheiros ausentes usam a versão incluída na aplicação. São fragmentos sem esquema próprio: os pedidos mantêm o esquema da tarefa. O espaço em branco final destes fragmentos é removido antes da composição.

O fragmento `repair-record-scope-instructions.md` orienta a reparação de uma única fatura ou movimento. A configuração `PROMPT_DIRECTORY` aceita um ficheiro com esse mesmo nome.

As mensagens de correção de categorias usam ainda `category-variant-mismatch-instructions.md`, `category-protected-variant-instructions.md`, `category-generalize-variant-instructions.md`, `category-generic-family-instructions.md`, `category-generalize-required-instructions.md` e `category-single-move-instructions.md`, com as mesmas regras de substituição e sem esquema próprio. Os marcadores `%s` devem ser conservados pela ordem original: nome da categoria e instrução de correção em `category-variant-mismatch-instructions.md`; ID de origem, nome geral e ID da família em `category-generalize-variant-instructions.md`; nome proposto e lista de IDs de origem em `category-generalize-required-instructions.md`.

A revisão automática de faturas, movimentos, documentos e afetações usa `model:review`. A mensagem de sistema vem de `automatic-review.md`. A mensagem do utilizador inclui a lista de campos com os valores guardados e a sua proveniência, o texto das fontes identificadas, as regras aplicáveis, `automatic-review.schema.json` e o exemplo de `automatic-review-example.md`. As listas de verificação vêm de `automatic-review-invoice.md`, `automatic-review-movement.md`, `automatic-review-document.md` e `automatic-review-allocation.md`. Os seis ficheiros Markdown admitem substituições UTF-8 em `PROMPT_DIRECTORY` com os mesmos nomes. `review.md` e `review.schema.json` continuam disponíveis para a análise de correções.

A revisão automática usa apenas texto. As afetações incluem, quando disponíveis, a fatura e o extrato em fontes distintas. Usa o texto integral quando as instruções, a entrada e o esquema cabem nos 50 000 caracteres. Pedidos maiores são revistos em excertos identificados, com cobertura parcial. Cada pedido exige que o fornecedor preserve o contexto integral. Se o fornecedor recusar o tamanho do pedido, o backend divide o texto mais uma vez quando há uma separação entre páginas ou linhas. O relatório identifica as partes que ficaram por rever.

Só uma divergência que o backend verifica pode reabrir o registo automático afetado. A verificação abrange a data de emissão ou total final explicitamente identificados e o montante ou direção de um movimento bancário inequívoco. Divergências em dados protegidos e afetações geram avisos para revisão humana. A cobertura insuficiente aparece como **Revisão inconclusiva**. O resultado agregado fica no trabalho da fila e as respostas do modelo ficam no histórico de execuções. A configuração aplica-se às próximas revisões.

## Testes e avaliação

O relatório dos testes e dos ensaios Docker/recuperação desta entrega está em [VALIDATION.md](VALIDATION.md).

O avaliador de documentos reais está documentado em [evaluation/README.md](evaluation/README.md). `AI_ENABLED=false` conserva as revisões na fila sem fazer chamadas de IA; `AI_HISTORY_MONTHS` pode limitar uma avaliação histórica por folha original. Estes parâmetros não confirmam nem corrigem dados financeiros.

Um PDF com comprovativos independentes pode ter um rascunho com `invoices`, uma lista de despesas separadas. O editor apresenta cada comprovativo e os registos continuam a partilhar o original. Respostas de IA com campos desconhecidos preservam os valores já extraídos; um parecer PASS não substitui a validação de campos e evidências.

```sh
mvn -f accounting/backend/pom.xml test
cd accounting/frontend && npm ci && npm run build
cd accounting/document-worker && uv sync --group test && uv run pytest
```

Os testes de interface usam Chromium, uma base de dados temporária nova e portas próprias (Java 18081 e Angular 14200), sem aceder às instalações de desenvolvimento:

```sh
cd accounting/frontend
npm ci
npx playwright install chromium
npm run test:e2e
```

Os testes de integração arrancam PostgreSQL 18, aplicam as migrações e usam HTTP real para autenticação/CSRF. Não necessitam de Docker. O ambiente tem de permitir processos locais e portas de loopback. Os testes HTTP do serviço Python não descarregam modelos; a conversão com modelos é uma verificação separada.

Para avaliar documentos reais, usar o procedimento em `evaluation/README.md`. Os resultados em documentos sintéticos não demonstram precisão nas faturas históricas. A aprovação de utilização depende de um conjunto separado, revisto manualmente, com os vários formatos reais e sem erros financeiros silenciosos nos registos aprovados automaticamente.

## Cópias e recuperação

Para a instalação local, encerrar a aplicação e aguardar a paragem do PostgreSQL. A cópia inclui a base de dados e os documentos no mesmo arquivo, com checksum:

```sh
bash accounting/scripts/backup-local.sh /caminho/novo/backup-local
bash accounting/scripts/restore-local.sh /caminho/backup-local /caminho/novo/dados
ACCOUNTING_DATA=/caminho/novo/dados bash accounting/scripts/run-local.sh
```

Esta cópia física exige o mesmo sistema operativo, arquitetura e versão principal PostgreSQL 18. A recuperação recusa diretórios existentes. Para Docker, usar a cópia lógica seguinte, que permite recuperar noutra instalação:

```sh
bash scripts/backup.sh /caminho/novo/backup
# Numa instalação nova com base de dados vazia:
bash scripts/restore.sh /caminho/backup
```

A cópia para a aplicação antes de capturar base de dados e originais, e volta a iniciá-la no final. A recuperação recusa uma base existente. Ensaiar a recuperação numa instalação separada e confirmar hashes, documentos e saldos. Guardar as credenciais/configuração por um canal separado. A aplicação não é um sistema de faturação nem substitui contabilidade geral ou submissões fiscais.

### Dados dos extratos bancários

A extração e a revisão usam apenas data de movimento, descrição completa, montante e débito/crédito. Saldos bancários iniciais, finais, atuais ou após movimento não são extraídos nem usados para aprovar, suspender ou editar movimentos. A conta é selecionada na importação; o original mantém-se arquivado. Os saldos do gestor e os controlos do Excel histórico continuam a seguir as regras financeiras documentadas.
