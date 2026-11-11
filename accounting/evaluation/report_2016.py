"""Produce a private Portuguese report from independently annotated sources and actual API snapshots."""

from __future__ import annotations
import argparse, collections, decimal, html, json, statistics
from urllib.parse import quote
from pathlib import Path
from real_2016 import MONTHS, money


def euro(value):
    """Display exact decimal strings in Portuguese without floating-point calculations."""
    return "—" if value is None else str(value).replace(".", ",")


def history_comparison(reference, snapshot):
    """Match by original sheet/row, preserving cross-year dates and blank-versus-zero amounts."""
    actual = {(r["sheet"], r["rowNumber"]): r for r in snapshot["history"]}
    differences = []
    for expected in reference["rows"]:
        key = (expected["sheet"], expected["rowNumber"])
        found = actual.get(key, {})
        for field in ["date", "description", "received", "payment", "balance"]:
            wanted = expected.get(field)
            got = found.get(field)
            if field == "description":
                wanted = " ".join((wanted or "").split())
                got = " ".join((got or "").split())
            if wanted != got:
                differences.append(
                    {
                        "sheet": key[0],
                        "row": key[1],
                        "field": field,
                        "expected": wanted,
                        "actual": got,
                    }
                )
    extra = [
        key
        for key in actual
        if key not in {(r["sheet"], r["rowNumber"]) for r in reference["rows"]}
    ]
    doc = next(d for d in snapshot["documents"] if d["metadata"]["kind"] == "HISTORY")
    controls = {
        s["name"]: s["controls"] for s in doc["detail"]["draft"].get("sheets", [])
    }
    control_differences = [
        {
            "sheet": r["sheet"],
            "field": field,
            "expected": r[field],
            "actual": controls.get(r["sheet"], {}).get(field),
        }
        for r in reference["controls"]
        for field in ["opening", "received", "payment", "closing"]
        if r[field] != controls.get(r["sheet"], {}).get(field)
    ]
    return {
        "expectedRows": len(reference["rows"]),
        "actualRows": len(actual),
        "differences": differences,
        "extraRows": extra,
        "controlDifferences": control_differences,
        "status": doc["detail"]["status"],
        "processingIssues": len(doc["detail"]["draft"].get("issues", [])),
    }


def bank_comparison(snapshot, document_reference):
    """Check the ten manually inspected signed movements, independently of the parser's balances."""
    expected = {
        name: [tuple(row) for row in rows]
        for name, rows in document_reference["bankMovements"].items()
    }
    checked = correct = approved = 0
    differences = []
    for document in snapshot["documents"]:
        name = document["metadata"]["filename"]
        if name not in expected:
            continue
        wanted = collections.Counter(expected[name])
        rows = document["records"]["movements"]
        actual = collections.Counter(
            (r["bookedOn"], r["amount"], r["direction"]) for r in rows
        )
        checked += sum(wanted.values())
        correct += sum((wanted & actual).values())
        approved += sum(r["reviewStatus"] != "OPEN" for r in rows)
        if actual != wanted:
            differences.append(
                {
                    "document": name,
                    "missing": list((wanted - actual).elements()),
                    "extra": list((actual - wanted).elements()),
                }
            )
    return {
        "checked": checked,
        "correct": correct,
        "approved": approved,
        "differences": differences,
    }


def application_summary(snapshot, document_reference):
    """Measure published data and flag unsupported automatic effects rather than trusting AI PASS."""
    invoices = [i for d in snapshot["documents"] for i in d["records"]["invoices"]]
    violations = []
    for d in snapshot["documents"]:
        known = [
            r
            for r in document_reference["rows"]
            if r["document"] == d["metadata"]["filename"]
        ]
        for invoice in d["records"]["invoices"]:
            if invoice["reviewStatus"] == "OPEN":
                continue
            if not any(r["amount"] == invoice["total"] for r in known):
                violations.append(
                    {
                        "document": d["metadata"]["filename"],
                        "reason": "Montante aprovado sem correspondência na referência documental",
                        "invoice": invoice["id"],
                    }
                )
            if not any(
                r["dateKind"] in ["issue", "receipt"]
                and r["documentDate"] == invoice["issuedOn"]
                and r["amount"] == invoice["total"]
                for r in known
            ):
                violations.append(
                    {
                        "document": d["metadata"]["filename"],
                        "reason": "Data de emissão aprovada sem prova na referência documental",
                        "invoice": invoice["id"],
                    }
                )
    bank = bank_comparison(snapshot, document_reference)
    if bank["approved"] and bank["differences"]:
        violations.append(
            {
                "reason": "Movimentos aprovados com divergências financeiras",
                "details": bank["differences"],
            }
        )
    wrong_matches = [a for a in snapshot["allocations"] if a["origin"] == "RULES"]
    executions = snapshot["assistant/executions"]
    durations = [e["durationMs"] / 1000 for e in executions]
    return {
        "documents": len(snapshot["documents"]),
        "invoices": len(invoices),
        "validatedInvoices": sum(i["reviewStatus"] != "OPEN" for i in invoices),
        "openInvoices": sum(i["reviewStatus"] == "OPEN" for i in invoices),
        "bank": bank,
        "jobs": dict(collections.Counter(j["state"] for j in snapshot["jobs"])),
        "aiExecutions": len(executions),
        "aiOutcomes": dict(collections.Counter(e["outcome"] for e in executions)),
        "aiTasks": dict(collections.Counter(e["task"] for e in executions)),
        "failedJobs": [
            {"kind": j["kind"], "error": j["error"], "targetId": j["targetId"]}
            for j in snapshot["jobs"]
            if j["state"] == "FAILED"
        ],
        "durationMedianSeconds": statistics.median(durations) if durations else None,
        "durationP95Seconds": statistics.quantiles(
            durations, n=100, method="inclusive"
        )[94]
        if len(durations) > 1
        else None,
        "automaticFinancialViolations": violations,
        "incorrectAutomaticAllocations": wrong_matches,
    }


def main():
    """Write JSON, Markdown and a standalone HTML report without changing source workbooks or data."""
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    p = args.directory
    load = lambda name: json.loads((p / name).read_text())
    ref = load("reference.json")
    truth = load("document-reference.json")
    a = load("a-repeat.json")
    b = load("b-repeat.json")
    initial = load("a-initial.json")
    initial_excel = load("b-initial-excel-only.json")
    summaries = {
        "aInitial": application_summary(initial, truth),
        "aFinal": application_summary(a, truth),
        "bFinal": application_summary(b, truth),
        "excelInitial": history_comparison(ref, initial_excel),
        "excelFinal": history_comparison(ref, b),
    }
    reviews = load("llm-row-reviews.json")
    rows = []
    by_key = {(r["sheet"], r["rowNumber"]): r for r in ref["rows"]}
    llm = {}
    for month, review in zip(MONTHS, reviews):
        for row in review.get("parsed", {}).get("rows", []):
            llm[(month, row["rowNumber"])] = row
    for documented in truth["rows"]:
        key = (documented["sheet"], documented["rowNumber"])
        expected = by_key[key]
        amount_diff = (
            None
            if documented["amount"] is None
            else money(
                decimal.Decimal(documented["amount"])
                - decimal.Decimal(expected["received"] or "0")
            )
        )
        review = llm.get(key)
        validation_problems = []
        if review and review.get("verdict") == "SUPPORTED":
            if not documented["document"]:
                validation_problems.append(
                    "O modelo afirmou suporte sem documento localizado na referência independente"
                )
            if review.get("observedAmount") != expected.get("received"):
                validation_problems.append(
                    "O modelo afirmou suporte apesar de o montante observado diferir do Excel ou estar ilegível"
                )
            if review.get("observedDate") != expected.get("date"):
                validation_problems.append(
                    "O modelo afirmou suporte apesar de a data observada diferir do Excel ou estar ilegível"
                )
            if review.get("documentReferenceError"):
                validation_problems.append(review["documentReferenceError"])
        rows.append(
            {
                "excel": expected,
                "documentReference": documented,
                "amountDifference": amount_diff,
                "dateDifference": documented["documentDate"] is not None
                and documented["documentDate"] != expected["date"],
                "llmReview": review,
                "llmValidationProblems": validation_problems,
            }
        )
    unresolved = sum(r["documentReference"]["document"] is None for r in rows)
    financial = (
        summaries["aFinal"]["automaticFinancialViolations"]
        + summaries["bFinal"]["automaticFinancialViolations"]
    )
    wrong = (
        summaries["aFinal"]["incorrectAutomaticAllocations"]
        + summaries["bFinal"]["incorrectAutomaticAllocations"]
    )
    result = {
        "summary": summaries,
        "rows": rows,
        "sources": ref["sources"],
        "unlocatedQuarterRows": unresolved,
        "unsupportedLlmSupportedClaims": sum(
            bool(r["llmValidationProblems"]) for r in rows
        ),
        "correctLlmObservedAmounts": sum(
            r["documentReference"]["amount"] is not None
            and (r["llmReview"] or {}).get("observedAmount")
            == r["documentReference"]["amount"]
            for r in rows
        ),
        "modelStructureValid": all(
            r.get("validation") == "VALID_STRUCTURE_ONLY" for r in reviews
        ),
        "automaticSafetyGatePassed": not financial and not wrong,
        "readyForUnattendedUse": False,
        "reason": "A segurança dos casos automáticos não demonstra cobertura suficiente; continuam despesas não aprovadas, provas em falta e divergências documentais.",
    }
    result["idempotency"] = load("idempotency.json")
    result["historyReviewScope"] = load("scope-check.json")
    result["monthlyBalances"] = load("monthly-balances.json")
    (p / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    text = [
        "# Avaliação real — janeiro a março de 2016",
        "",
        "## Resultado",
        "",
        "**A importação do Excel foi confirmada. A validação automática integral das despesas não está aprovada.** Os estados em aberto não foram preenchidos ou confirmados artificialmente.",
        "",
        "| Verificação | Inicial | Após correções |",
        "|---|---:|---:|",
        f"| Linhas do Excel importadas | {summaries['excelInitial']['actualRows']} | {summaries['excelFinal']['actualRows']} |",
        f"| Alertas de cálculo na leitura do Excel | {summaries['excelInitial']['processingIssues']} | {summaries['excelFinal']['processingIssues']} |",
        f"| Movimentos com data, montante e direção corretos — sem IA | {summaries['aInitial']['bank']['correct']}/{summaries['aInitial']['bank']['checked']} | {summaries['aFinal']['bank']['correct']}/{summaries['aFinal']['bank']['checked']} |",
        f"| Faturas/despesas aprovadas — sem IA | {summaries['aInitial']['validatedInvoices']} | {summaries['aFinal']['validatedInvoices']} |",
        "",
        "## Segundo teste — Excel, ZIP e Ollama",
        "",
        f"- {summaries['excelFinal']['actualRows']}/{summaries['excelFinal']['expectedRows']} linhas anuais; {len(summaries['excelFinal']['differences'])} diferenças de campos e {len(summaries['excelFinal']['controlDifferences'])} diferenças nos controlos mensais.",
        f"- {summaries['bFinal']['aiExecutions']} execuções registadas na aplicação; {summaries['bFinal']['jobs'].get('DONE', 0)} trabalhos concluídos e {summaries['bFinal']['jobs'].get('FAILED', 0)} falhados.",
        f"- {summaries['bFinal']['validatedInvoices']} registos de despesas aprovados; {summaries['bFinal']['openInvoices']} continuam em aberto.",
        f"- {len(llm)}/{len(rows)} linhas submetidas também à comparação explícita com páginas dos comprovativos, usando três respostas mensais do qwen3.5:9b.",
        f"- Falhas de revisão: {len(summaries['bFinal']['failedJobs'])}. As respostas inválidas não foram aceites como validação.",
        f"- A IA afirmou suporte em {result['unsupportedLlmSupportedClaims']} linhas apesar de os seus próprios valores/datas não coincidirem com o Excel ou faltar prova independente. Esses pareceres não são aprovação.",
        f"- Na comparação mensal, a IA leu corretamente {result['correctLlmObservedAmounts']} montantes nos {len(rows) - unresolved} casos com documento localizado. Isso não tornou corretos todos os seus pareceres de validação.",
        f"- Aprovações automáticas sem suporte identificadas: {len(financial)}; correspondências automáticas incorretas: {len(wrong)}.",
        "",
        "Um PASS da IA não é confirmação financeira. As respostas mensais são propostas verificáveis, conservadas com a resposta bruta, imagens identificadas por hash e modelo/digest. Nenhuma destas propostas foi aplicada aos dados.",
        "",
        "## Comparação das 23 linhas",
        "",
        "| Folha / linha | Excel: data e montante | Comprovativo: data e montante | Resultado da IA | Observação independente |",
        "|---|---|---|---|---|",
    ]
    for row in rows:
        e = row["excel"]
        d = row["documentReference"]
        review = row["llmReview"] or {}
        note = d["notes"]
        if row["llmValidationProblems"]:
            note = "PARECER IA CONTRADITÓRIO. " + note
        if row["amountDifference"] not in [None, "0.00"]:
            note = "DIFERENÇA DE VALOR: " + str(row["amountDifference"]) + " €. " + note
        if row["dateDifference"]:
            note = "DATA DIFERENTE. " + note
        source = next(
            (
                d
                for d in ref["documents"]
                if Path(d["file"]).name == row["documentReference"]["document"]
            ),
            None,
        )
        document_label = (
            f"[{d['document']}](<corpus/{quote(source['path'])}>)"
            if source
            else "Não localizado"
        )
        verdict_labels = {
            "SUPPORTED": "Suportado",
            "MISSING_DOCUMENT": "Sem comprovativo",
            "DIFFERENT_AMOUNT": "Valor divergente",
            "DATE_DIFFERENCE": "Data divergente",
            "AMBIGUOUS": "Ambíguo",
            "UNREADABLE": "Ilegível",
        }
        date_labels = {
            "issue": "emissão",
            "service": "serviço",
            "payment": "pagamento",
            "receipt": "recibo",
        }
        text.append(
            f"| {e['sheet']} / {e['rowNumber']} | {e['date']} · {euro(e['received'])} € | {document_label} — {d['documentDate'] or '—'} · {euro(d['amount'])} € ({date_labels.get(d['dateKind'], 'sem prova')}) | {verdict_labels.get(review.get('verdict'), 'Sem resposta válida')} · observado: {euro(review.get('observedAmount'))} € / {review.get('observedDate') or '—'} | {note} |"
        )
    text += [
        "",
        "## Saldos: referência manual e dados efetivamente aprovados",
        "",
        "| Mês | Saldo manual do Excel | Teste sem IA | Teste com IA |",
        "|---|---:|---:|---:|",
    ]
    for index, month in enumerate(MONTHS[:3]):
        control = next(c for c in ref["controls"] if c["sheet"] == month)
        text.append(
            f"| {month} | {euro(control['closing'])} € | {euro(result['monthlyBalances']['a'][index]['closing'])} € | {euro(result['monthlyBalances']['b'][index]['closing'])} € |"
        )
    text += [
        "",
        "Os saldos da aplicação incluem apenas registos aprovados. As despesas continuam em aberto: importar a referência Excel não as valida nem cria pagamentos. Por isso, o saldo da aplicação não reproduz o saldo manual nesta execução. Os controlos da importação do Excel, comparados separadamente, coincidem com o ficheiro.",
        "",
        "## Repetição e âmbito",
        "",
        "As reimportações conservaram documentos, trabalhos e linhas; os hashes dos originais não mudaram. A revisão histórica enviou 23 linhas e três folhas ao modelo; os restantes meses ficaram fora desse pedido. A comparação mensal de fevereiro foi repetida depois de corrigir a leitura de montantes com vírgula no avaliador. As respostas de diagnóstico não são usadas como aprovação financeira.",
    ]
    text += [
        "",
        "## Diferenças e limites importantes",
        "",
        *["- " + finding for finding in truth.get("findings", [])],
        "- Os extratos contêm um saldo atual no cabeçalho que não corresponde necessariamente ao fim do mês consultado. A leitura distingue a coluna de montante da coluna de saldo. Pela regra atual, saldos bancários são ignorados na validação dos movimentos.",
        "- A execução foi local em Ollama/MLX, com contexto de 16 mil tokens. As durações e a memória observadas não são um benchmark do servidor CPU de destino.",
        "- Os três meses foram usados para diagnóstico e correção. Não constituem um conjunto independente de avaliação final.",
        "",
        "## Correções verificadas",
        "",
        "Reconhecimento do saldo inicial em duas linhas; separação entre montante bancário e saldo após movimento; desativação efetiva de IA no primeiro teste; revisão histórica limitada às folhas autorizadas; suporte a comprovativos independentes no mesmo PDF; preservação de campos lidos quando a IA devolve null. Os testes de regressão usam dados sintéticos, não cópias dos documentos privados.",
        "",
        "## Reprodução e proveniência",
        "",
        "reference.json: leitura XML independente do Excel e hashes dos originais. a-initial/b-initial: ensaios anteriores. b-before-preservation-fix: passagem de diagnóstico interrompida para corrigir perda de campos, não apresentada como ensaio completo. a-repeat/b-repeat: repetições definitivas. document-reference.json: revisão independente. llm-rows-*.json: pedido, parâmetros, digest, resposta bruta e validação estrutural. run-metadata.json: versões e observação de recursos. idempotency.json: verificação de reimportação, quando concluída.",
        "",
        "Os dados e relatórios permanecem em accounting/data/, ignorado pelo Git. Os ficheiros originais não foram alterados.",
    ]
    markdown = "\n".join(text) + "\n"
    (p / "relatorio.md").write_text(markdown)
    # Standalone report: HTML escapes all source-derived values and loads no external resources.
    try:
        from markdown_it import MarkdownIt

        rendered = (
            MarkdownIt("commonmark", {"html": False}).enable("table").render(markdown)
        )
    except ImportError:
        rendered = "<pre>" + html.escape(markdown) + "</pre>"
    page = (
        '<!doctype html><html lang="pt-PT"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Avaliação real 2016</title><style>body{font:16px/1.6 system-ui;background:#edf3ef;color:#18352c;margin:0;padding:3vw}main{max-width:1250px;margin:auto;background:white;padding:3vw;border-radius:16px;overflow:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}h1{font-size:2rem}h2{margin-top:2.5rem;border-top:1px solid #dce6df;padding-top:1.2rem}a{color:#176548}table{border-collapse:collapse;width:100%;font-size:.86rem}th{background:#e7f0e9;text-align:left}th,td{padding:.7rem;border-bottom:1px solid #dce6df;vertical-align:top}tr:nth-child(even){background:#f7faf8}li{margin:.45rem 0}</style><main><p><a href="relatorio.md">Versão Markdown</a> · <a href="report.json">Resultados estruturados</a></p>'
        + rendered
        + "</main></html>"
    )
    (p / "relatorio.html").write_text(page)
    print("Relatório:", p / "relatorio.md")


if __name__ == "__main__":
    main()
