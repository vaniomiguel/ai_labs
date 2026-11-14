# Verificação da entrega — 11 de novembro de 2026

## Proteção dos valores humanos — 14 de novembro de 2026

A origem dos campos passa a ser persistida em faturas, movimentos e rascunhos documentais. Edições protegem os campos alterados, incluindo limpezas explícitas; validações protegem os valores preenchidos. Um serviço comum impede substituições por extração, reprocessamento ou correção automática. Divergências ficam como sugestões, e os artigos são tratados como uma coleção protegida. A migração V4 recupera participação humana e limpezas identificadas na auditoria, usando proteção conservadora para edições documentais antigas ambíguas, sem alterar originais, montantes ou lançamentos.

O diálogo distingue complementos de conflitos, mostra evidências e proteção, e exige **Aceitar valor da IA** por campo para substituir valores. As escolhas podem ser revertidas antes de guardar. **Aplicar ao rascunho** permite resultados incompletos e **Aplicar proposta e validar** verifica o resultado combinado no servidor. A seleção limita a operação aos itens escolhidos e conserva edições locais dos irmãos. Guardar uma substituição no rascunho de um item publicado conserva o valor financeiro anterior até validação. As respostas expõem `fieldProvenance`; `/alerts/{id}/preview` não escreve dados e `/alerts/{id}/apply` valida a versão, a proposta e as seleções, retirando os valores exclusivamente da proposta guardada.

Verificação: **152 testes Java aprovados** com Maven `verify`, incluindo 20 novos testes de proteção e aplicação, um de migração V3→V4 e dois contratos adicionais de correção por IA. Cobrem proteção de campos e limpezas, validação, normalização de valores, conflitos por campo, rascunhos incompletos, alterações durante análise, irmãos reordenados, evidência na ordem original, categorias, exclusões, auditoria, repetições e conservação financeira. **29 testes Playwright aprovados**, incluindo quatro novos cenários de escolhas por campo, preservação de edições durante análise, rascunho incompleto com teclado a 390 px e seleção de um único comprovativo. A compilação Angular de produção e as 157 referências internas OpenAPI passaram. Os testes usam respostas de IA controladas e bases temporárias; a migração da instalação em utilização fica para o próximo arranque do backend.

## Registos por validar visíveis e editáveis — 14 de novembro de 2026

As faturas e os movimentos `OPEN` sem data mantêm-se visíveis nas três listagens, primeiro na paginação e independentemente do período selecionado. Os registos datados respeitam os filtros. O nome do original pode identificar e pesquisar entradas sem fornecedor ou descrição. O marcador **Por validar** e os montantes provisórios distinguem-nos dos registos elegíveis; o impacto financeiro e a disponibilidade para associar ficam vazios até validar. Exclusão e validação são estados independentes.

**Rever e validar** carrega o item atual no editor individual, com o original ao lado e os dados em falta. A sincronização altera só a entrada identificada no rascunho, preservando as outras entradas, os metadados e as suas validações. A conclusão do documento e do extrato depende dos restantes itens. Versões documentais antigas e identidades ambíguas são rejeitadas; dados antigos com identidade inequívoca podem voltar a ligar-se ao item. As respostas e os contratos incluem `documentVersion`, verificações de revisão e o indicador `pendingReview` nas entradas de reconciliação. Não foi acrescentada uma migração de base de dados.

Verificação: **129 testes Java aprovados** através de Maven `verify`, incluindo 16 novos testes de visibilidade, filtros, paginação, isolamento financeiro, validação individual, preservação de categorias/exclusões, identidades e conflitos. **25 testes Playwright aprovados**, sete novos para este fluxo e 18 de regressão. Incluem a abertura do PDF real, pesquisa pelo nome do original, preservação dos filtros após validar, faturas e extratos com várias entradas, montantes/direção desconhecidos, teclado e ecrã de 390 px. A compilação Angular de produção, referências internas OpenAPI e revisão visual das capturas também passaram. Os ensaios usam bases temporárias e não validam dados da instalação em utilização.

## Progresso e resultado das correções por IA — 13 de novembro de 2026

O diálogo acompanha agora cada tentativa, com confirmação imediata, spinner durante processamento e atualização automática da proposta. Pedidos concorrentes reutilizam a tarefa ativa; novas análises explícitas fazem nova chamada ao modelo. A proposta compara campos por comprovativo, apresenta observações e bloqueia a aplicação de dados incompletos ou desatualizados. Atualizações preservam as edições locais; respostas antigas não substituem outro documento nem reabrem um diálogo fechado.

As correções usam o texto integral guardado e, em modelos visuais, todas as páginas do PDF original, independentemente do layout extraído. O transporte desativa truncagem e deslocamento de contexto no Ollama. Limites de entrada, páginas e contexto são configuráveis; excesso de capacidade é uma falha permanente visível. Modelo, versão documental, páginas e evidências ficam no histórico. A funcionalidade usa os campos de metadados e a fila existentes, sem nova migração de base de dados.

Verificação: **113 testes Java aprovados**, incluindo 18 novos cenários de integração de correções e o contrato de recusa de contexto do Ollama; **18 testes Playwright aprovados**, oito dedicados a correções e dez às categorias/reconciliação. Os ensaios verificam pedidos concorrentes, indisponibilidade, falhas estruturadas, idempotência, versões antigas, propostas incompletas, ausência de alterações, texto superior ao antigo limite de fragmentação, páginas sem metadados e preservação dos efeitos financeiros até à confirmação. No navegador verificaram-se o spinner, atualização automática, fecho/reabertura, edições locais, respostas atrasadas e apresentação a 390 px. Compilação Angular de produção e referências internas OpenAPI verificadas.

Os novos testes usam respostas de IA controladas e bases temporárias; não medem a precisão da correção em documentos reais nem aplicam propostas aos documentos da instalação em utilização.

## Categorias evolutivas — 12 de novembro de 2026

Implementado um catálogo partilhado por faturas e movimentos, com várias etiquetas, famílias, sinónimos, proveniência e decisões manuais. A migração V3 conserva as categorias antigas como protegidas e introduz a referência OCC/SNC de fevereiro de 2019 sem duplicar nomes ou sinónimos equivalentes. A classificação tem versões próprias, tarefas persistentes e herança dinâmica das faturas associadas; os saldos e as regras de reconciliação mantêm-se independentes.

**94 testes Java aprovados**, sem falhas ou testes ignorados, através de Maven `verify`. Incluem migrações V1–V3 e V2–V3 com dados existentes, generalização USB-C/USB-A, categorias mistas, filtros por descendentes sem duplicações, herança agrupada, desassociação, rejeições manuais, respostas inválidas, estados de indisponibilidade/falha por geração da tarefa, resultados concorrentes desatualizados e proteção de referências. O desfazer conserva novas utilizações gerais, remoções e escolhas manuais posteriores. As verificações financeiras confirmam versões e lançamentos inalterados pelas decisões de categorias; alertas exclusivamente sobre etiquetas não suspendem dados financeiros.

**Dez testes Playwright aprovados**, cinco dedicados às categorias e cinco à reconciliação, com Chromium, API e PostgreSQL reais numa instalação temporária. Cobrem seleção múltipla, origem/justificação acessível, remoção manual, herança após edição da fatura, renomeação histórica, desbloqueio em lote, fusão/desfazer e editor a 390 px com teclado. As capturas do catálogo e da vista móvel foram inspecionadas. A compilação Angular de produção e as referências internas do OpenAPI passaram.

**Oito casos da avaliação com Ollama aprovados**, usando `qwen3.5:9b` e o adaptador/serviço de classificação reais. O resumo versionado está em `evaluation/category-model-result.json`; o procedimento reproduzível e os limites estão em `evaluation/README.md`. Uma resposta inicial de criação foi rejeitada por não generalizar o histórico e corrigida numa segunda chamada. Uma atribuição numa transferência genérica foi impedida pela proteção do serviço e ficou em revisão. Estes resultados validam o fluxo com as proteções, não apenas o JSON inicial do modelo, nem a precisão geral em documentos reais.

Os ensaios usam dados sintéticos e bases descartáveis. A instalação com dados do utilizador recebe a migração V3 no próximo arranque do backend; não foi alterada por estes testes.

## Reconciliação completa — 12 de novembro de 2026

Implementadas as entradas sem correspondência, o efeito integral dos débitos/créditos e a exclusão reversível de faturas e movimentos. As associações organizam os registos numa linha por grupo, sem duplicar efeitos financeiros. As diferenças de data e valor ficam visíveis; grupos entre períodos mostram o saldo global e o impacto do período em separado.

Verificação local: **69 testes Java aprovados**, incluindo uma atualização de PostgreSQL V1 com dados e associações existentes para V2. O ensaio confirma a preservação dos lançamentos antigos e das associações, as anulações datadas, os novos sinais bancários e duas sincronizações da aplicação sem lançamentos adicionais. A suite cobre ainda exclusão de cada lado e de ambos, seleção parcial em grupos, estornos dependentes, reinclusão, dados por validar, versões antigas, concorrência e preservação do marcador ao republicar ou reordenar movimentos e comprovativos. A identificação usa os registos e referências guardados; uma alteração ambígua não transfere uma exclusão para outra linha.

**Cinco testes Playwright aprovados**, com API e PostgreSQL reais numa instalação temporária: entrada sem fatura e correspondência posterior; exclusão/reinclusão de cada lado e de ambos; pagamentos agrupados; datas entre anos; navegação por teclado e apresentação num ecrã de 390 px. A compilação Angular de produção, Maven `verify`, as referências internas OpenAPI e a sintaxe do lançador de testes também passaram. Os testes de interface foram acrescentados ao workflow de integração contínua; a execução alojada ainda depende desse workflow.

Estes ensaios usam dados sintéticos e uma base isolada. A migração da instalação do utilizador ocorre no próximo arranque com o novo backend; essa instalação não foi alterada pelos testes.

## Ajuste dos critérios bancários

Os extratos são agora validados apenas por data, descrição, montante e débito/crédito. Foram retiradas a exigência e a comparação de saldos bancários, incluindo das prompts e do editor. Os três extratos reais foram reprocessados nas duas instalações: dez movimentos corretos e validados em cada uma, com descrições completas e sem saldos nos rascunhos. Os 42 testes Java passaram. As verificações do saldo do gestor e do Excel histórico mantêm-se. Os resultados anteriores abaixo documentam a versão então ensaiada.

## Atualização com documentos reais

Foram executados os dois ensaios de 2016: documentos individuais sem IA e Excel seguido do ZIP selecionado, com revisão local por `qwen3.5:9b`. Os resultados, referências, respostas brutas, base de dados de auditoria e relatórios permanecem em `data/evaluation/2016-Q1/`, fora do Git. Os originais foram preservados e a reimportação foi verificada sem duplicação.

As falhas de leitura dos controlos Excel e das colunas bancárias foram corrigidas; foram acrescentados suporte a vários comprovativos por original, controlo de âmbito da revisão e preservação de campos conhecidos. A validação automática integral continua **não aprovada**: há despesas em aberto, pareceres contraditórios do modelo e uma revisão com respostas JSON inválidas. O relatório privado distingue importação técnica, evidência documental e aprovação financeira.

Verificação atual do código: **39 testes Java, seis testes do avaliador e quatro testes do serviço documental aprovados**, além da compilação Angular e da imagem Docker usada nos ensaios.

## Resultados da verificação inicial

| Verificação | Resultado |
|---|---|
| Maven `verify`, Java 25 e PostgreSQL 18.6 real | 34 testes, zero falhas, zero testes ignorados; JAR produzido |
| Angular 22, compilação de produção | Concluída; PDF.js, fontes, mapas de caracteres e descodificadores locais |
| Serviço documental Python 3.12 | 4 testes HTTP concluídos |
| Avaliador de corpus | 3 testes de contagem, cobertura e erros financeiros concluídos |
| Instalação Docker Compose | Imagens Java/interface e Python construídas; aplicação, PostgreSQL 18 e serviço documental iniciados |
| OCR real dentro do Docker | Docling 2.150.0 + Tesseract `por`/`eng`, CPU: PDF sintético digitalizado com total `123,45`, texto e coordenadas recuperados, incluindo após reinício |
| Recuperação Docker | Cópia lógica da base de dados e arquivo dos documentos recuperados numa segunda instalação vazia |
| Proteção da recuperação | Nova tentativa sobre a base preenchida recusada antes de qualquer substituição |
| Cópia local com a aplicação parada | Checksum verificado; conteúdo de todos os ficheiros recuperados igual ao original |
| Interface no navegador | Sessão, conta, fatura manual, parcela de pagamento e editor de PDF lado a lado verificados |
| Progresso em segundo plano | Eventos autenticados com contagem e revisão confirmados na imagem Docker final |
| Contrato e scripts | Referências internas OpenAPI válidas; sintaxe dos scripts de arranque/cópia/recuperação verificada |

Os 34 testes Java incluem o reinício anual, dezembro/fevereiro, saldos 3/4/6, parcelas, agrupamentos, estornos, notas de crédito, transferências do gestor, limites de atribuição concorrente, versões antigas, ambiguidades posteriores, preservação de decisões humanas, pesquisa PostgreSQL, importação Excel/ZIP em ambas as ordens, fórmulas, duplicação de documentos, campos parcialmente inválidos e retomada da fila. O contrato Ollama é exercitado por um servidor HTTP de teste, incluindo catálogo, capacidades, imagens e esquemas.

No ensaio Docker de recuperação, uma fatura de **100,00 € em dezembro de 2025** e uma atribuição de **60,00 € em fevereiro de 2026** produziram **+100,00 € em 2025**, **−60,00 € em fevereiro de 2026** e **40,00 € por reconciliar**. Os mesmos valores, a associação e o hash SHA-256 do PDF original foram confirmados após recuperação. As instalações de ensaio usaram apenas dados sintéticos e volumes separados.

## Limites da validação

Na verificação inicial não tinham sido disponibilizados os Excel, faturas ou extratos históricos reais. Os ensaios posteriores acima não aprovam a precisão geral por formato bancário, artigos, datas, correspondências ou modelos. O exemplo OCR inicial é funcional e sintético. Não foi executada uma comparação dos modelos recomendados nem medido o consumo de memória no servidor CPU de destino; os ensaios posteriores usaram o modelo local com runner MLX.

Antes de usar a aplicação para decisões financeiras, preparar a referência manual e executar `evaluation/README.md` numa instalação isolada. O critério de aprovação continua a ser zero erros financeiros silenciosos e zero correspondências automáticas incorretas nesse conjunto. Casos incompletos ficam em revisão e as revisões sem um modelo disponível permanecem pendentes.

O fluxo de integração contínua foi adicionado em `.github/workflows/accounting.yml`; os comandos equivalentes foram executados localmente. A execução alojada do fluxo, HTTPS no servidor de destino e a política externa de retenção das cópias dependem da instalação final.
