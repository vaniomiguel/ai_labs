# Avaliação com documentos reais

## Categorias evolutivas com o modelo local

Executar na raiz do repositório, com Ollama disponível no endereço configurado e o modelo de categorias instalado (por omissão `qwen3.5:9b`):

```sh
bash accounting/scripts/evaluate-categories.sh
```

O ensaio usa uma base PostgreSQL temporária e casos sintéticos com critérios revistos no código de `CategoryModelEvaluation`: uma categoria automática USB-C é preparada como ponto de partida; o modelo recebe depois USB-A, HDMI/rato, seguros, contabilidade, uma fatura mista e uma descrição bancária ambígua. A revisão global confirma a conservação das famílias de referência. Cada caso verifica que a classificação não altera os lançamentos financeiros. A execução termina com código diferente de zero se um critério falhar.

O relatório completo, com entradas, respostas, operações, digest do modelo e resultados das validações, fica em `backend/target/category-model-evaluation.json`. Pode indicar outro destino como primeiro argumento do script; caminhos relativos são resolvidos a partir de `backend/`. O resumo da execução de 12/11/2026 está em [category-model-result.json](category-model-result.json): oito casos aprovados com `qwen3.5:9b`, incluindo uma resposta corrigida após rejeição de domínio e uma classificação ambígua impedida pela proteção de descrições bancárias genéricas. A avaliação mede o fluxo completo com estas proteções, não apenas a resposta inicial do modelo.

Uma execução local com temperatura zero não demonstra precisão geral nem substitui uma avaliação independente dos documentos da empresa. Para comparar modelos, repetir os mesmos casos e conservar os relatórios de cada digest.

## Ensaios anuais e comparação documental

`real_2016.py` prepara uma referência independente a partir das células, fórmulas e valores guardados do Excel e seleciona os PDF de **Despesas** e os extratos de janeiro–março do ZIP. Não importa outros documentos mensais como se fossem despesas. O diretório de resultados deve ser novo e privado, dentro de `accounting/data/` ou noutro local fora do Git.

```sh
python3 accounting/evaluation/real_2016.py prepare \
  --workbook /caminho/Excel-anual.xlsx --archive /caminho/arquivo-anual.zip \
  --output accounting/data/evaluation/novo-ensaio
```

Executar `individual` numa instalação isolada com `AI_ENABLED=false`; executar `history` numa segunda instalação vazia com `AI_ENABLED=true` e `AI_HISTORY_MONTHS=1,2,3`. Este último parâmetro restringe a revisão do Excel às folhas originais desses meses, incluindo linhas cuja data pertença ao ano anterior. O Excel anual é importado integralmente. `ADMIN_PASSWORD` é passado no ambiente; `--url`, `--name` e `--output` identificam cada ensaio. A fase `history` também recebe `--workbook`. Os snapshots anteriores não são substituídos.

`review_history.py` faz uma comparação adicional de cada linha mensal com texto e imagens dos originais, sem aplicar alterações. Deve correr apenas depois de a fila de IA da aplicação terminar, usando o Python do serviço documental, que contém PDFium/Pillow. `report_2016.py` combina snapshots com a referência documental revista independentemente. A referência de documentos, os montantes esperados dos movimentos e as observações pertencem aos ficheiros privados de resultados, não ao código nem aos testes de regressão.

O resultado deve separar importação técnica, cobertura, correção dos valores e qualidade da revisão da IA. Um PASS do modelo ou um resultado JSON válido não comprova que os valores estão corretos. Documentos de atualização de uma reserva, várias despesas no mesmo PDF, datas de viagem e datas de emissão exigem comparação explícita.

## Avaliador de casos anotados

Usar uma instalação isolada: o avaliador importa documentos e pode criar registos e associações. Não executar sobre a contabilidade em utilização.

Preparar um conjunto de desenvolvimento e outro de avaliação final, sem documentos repetidos entre ambos. Incluir documentos desde 2016, fornecedores diferentes, digitalizações, tabelas de várias páginas, valores semelhantes, pagamentos parciais e extratos de cada banco/formato. Rever manualmente a referência antes de medir os modelos.

Criar um manifesto privado junto dos ficheiros:

```json
{
  "expectedAllocations": [],
  "cases": [
    {
      "name": "fatura-simples",
      "file": "fatura.pdf",
      "kind": "INVOICE",
      "expected": {
        "invoice": {"issuedOn": "2026-11-11", "total": "123.45", "currency": "EUR", "type": "INVOICE"},
        "automatic": true
      }
    },
    {
      "name": "extrato",
      "file": "extrato.pdf",
      "kind": "STATEMENT",
      "expected": {
        "movements": [
          {"bookedOn": "2026-11-12", "description": "Pagamento fornecedor", "amount": "123.45", "direction": "DEBIT"}
        ]
      }
    }
  ]
}
```

Executar com a credencial da instalação isolada na variável `ADMIN_PASSWORD`:

```sh
python3 evaluation/evaluate.py /caminho/privado/manifest.json \
  --account-id UUID_DA_CONTA \
  --wait-seconds 3600 \
  --output data/avaliacao/resultado.json
```

Os montantes de referência são strings decimais e as datas são ISO. O relatório distingue campos corretos, diferenças, erros financeiros aceites, tarefas pendentes e falhas. Uma instalação sem modelo não conclui a avaliação de IA; aguardar tarefas não equivale a aprovação. O relatório é privado e deve permanecer fora do Git.

O campo `expectedAllocations` anota os pagamentos esperados, por exemplo `[{"invoice":"fatura-simples","statement":"extrato","movementIndex":0,"amount":"123.45"}]`. A posição do movimento começa em zero e respeita a ordem extraída. Uma lista vazia significa que não são esperadas associações automáticas. O relatório identifica associações automáticas incorretas e esperadas que faltam. Para comparar modelos, usar uma instalação isolada vazia em cada execução, evitando reutilizar resultados históricos. Exigir zero associações automáticas incorretas e zero erros financeiros silenciosos no conjunto final. Medir também cobertura automática, artigos extraídos, p50/p95 de tempo por documento e memória no servidor CPU real. Os tamanhos dos modelos e os testes sintéticos não substituem estas medições.
