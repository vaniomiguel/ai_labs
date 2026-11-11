# Verificação da entrega — 11 de novembro de 2026

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
