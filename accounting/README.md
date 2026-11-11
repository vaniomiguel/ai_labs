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
- Pagamentos atribuídos reduzem o saldo na data do movimento; estornos revertem esse efeito.
- Cada janeiro começa em zero. Os meses seguintes recebem o saldo do anterior dentro do mesmo ano.
- Fatura de dezembro de 100 € e pagamento em fevereiro de 60 €: +100 € no ano antigo, −60 € no novo, 40 € por reconciliar na fatura.
- Reembolsos/adiantamentos ao gestor reduzem o saldo; devoluções à empresa aumentam-no. Outros débitos/créditos não têm efeito sem associação ou classificação explícita.
- As associações aceitam pagamentos parciais e agrupados. Uma secção crítica em PostgreSQL impede atribuir duas vezes o mesmo valor.
- Dentro do mesmo ano consideram-se todos os meses. Entre anos usa-se a distância de meses de calendário, três por omissão: dezembro–março permitido, dezembro–abril excluído.
- Categorias são etiquetas, não repartições monetárias. Somar categorias diferentes pode contar a mesma fatura mais de uma vez.

Os efeitos financeiros são lançamentos imutáveis com anulações datadas. A vista **Saldos** discrimina entradas, reduções e os lançamentos de cada mês, com acesso aos originais disponíveis. Eliminar um registo não elimina o documento nem o histórico. O saldo não comprova que uma fatura sem correspondência foi paga pessoalmente.

## Extração, revisão e histórico

As importações são assíncronas e persistentes. Cada documento é arquivado com SHA-256. Os estados de processamento, revisão e reconciliação são independentes. Guardar sem resolver conserva o rascunho e exclui os dados afetados dos saldos. Validar aplica as mesmas regras usadas nas criações manuais.

Os parsers genéricos são conservadores: totais ambíguos, artigos não reconhecidos ou movimentos sem data, descrição, montante ou direção exigem revisão. Não existem formatos universais de PDF bancário; a leitura pode precisar de IA ou correção manual. O original pode ser consultado lado a lado com os campos e cada linha extraída.

O Excel histórico é uma referência, nunca uma fonte de pagamentos bancários inventados. As linhas e fórmulas originais ficam guardadas, os totais não viram despesas e diferenças com documentos geram alertas. A importação ZIP retoma por entrada e volta a usar documentos idênticos. Coincidências bancárias entre extratos diferentes exigem confirmação para não descartar transações legítimas iguais.

O mapeamento de Excel pode ser guardado em **Histórico**; reprocessar depois o documento. As colunas são índices a partir de zero no contrato da API. As folhas, células, fórmulas e resultados estão disponíveis na revisão.

## IA e auditoria

As interfaces `AiProvider` e `DocumentConverter` isolam o domínio dos fornecedores. O adaptador implementado usa a API nativa Ollama, incluindo imagens e esquemas. Outro fornecedor implementa essas interfaces sem alterar os cálculos.

As prompts e os esquemas estão centralizados em `backend/src/main/resources/prompts/`. `PROMPT_DIRECTORY` permite substituir os ficheiros Markdown sem recompilar. Cada execução guarda o hash da prompt, digest do modelo, entrada, resposta, duração e validação do esquema. Mudar a configuração aplica-se às próximas tarefas; não reprocessa silenciosamente o histórico.

A IA revê casos novos/alterados. Não confirma ambiguidades, não altera decisões humanas e não aplica propostas de correção sem confirmação. Revisões por regras não ficam bloqueadas por uma indisponibilidade do Ollama. Um conflito fundamentado suspende apenas dados automáticos afetados. A revisão pelo mesmo modelo não é uma validação independente.

## Testes e avaliação

O relatório dos testes e dos ensaios Docker/recuperação desta entrega está em [VALIDATION.md](VALIDATION.md).

O avaliador de documentos reais está documentado em [evaluation/README.md](evaluation/README.md). `AI_ENABLED=false` conserva as revisões na fila sem fazer chamadas de IA; `AI_HISTORY_MONTHS` pode limitar uma avaliação histórica por folha original. Estes parâmetros não confirmam nem corrigem dados financeiros.

Um PDF com comprovativos independentes pode ter um rascunho com `invoices`, uma lista de despesas separadas. O editor apresenta cada comprovativo e os registos continuam a partilhar o original. Respostas de IA com campos desconhecidos preservam os valores já extraídos; um parecer PASS não substitui a validação de campos e evidências.

```sh
mvn -f accounting/backend/pom.xml test
cd accounting/frontend && npm ci && npm run build
cd accounting/document-worker && uv sync --group test && uv run pytest
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
