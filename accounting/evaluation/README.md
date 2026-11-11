# Avaliação com documentos reais

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
