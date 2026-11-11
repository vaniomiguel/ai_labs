"""Evaluate an annotated private corpus against an isolated running Conta installation."""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal
import http.cookiejar
import json
import os
from pathlib import Path
import statistics
import time
import urllib.parse
import urllib.request
import uuid


def normalized(value):
    """Normalize whitespace only; never hide a monetary or date discrepancy."""
    return " ".join(value.split()) if isinstance(value, str) else value


def score(expected: dict, records: dict) -> dict:
    """Separate automation coverage from financially incorrect automatic acceptance."""
    checked = correct = silent = 0
    differences = []
    invoices = records.get("invoices", [])
    if "invoice" in expected:
        actual = invoices[0] if len(invoices) == 1 else {}
        eligible = actual.get("reviewStatus") in {"AUTOMATIC", "MANUAL", "CORRECTED", "EDITED"}
        for field, value in expected["invoice"].items():
            checked += 1
            if normalized(actual.get(field)) == normalized(value):
                correct += 1
            else:
                differences.append({"field": field, "expected": value, "actual": actual.get(field)})
                if eligible and field in {"total", "issuedOn", "currency", "type"}:
                    silent += 1
        if "automatic" in expected and bool(expected["automatic"]) != (actual.get("reviewStatus") == "AUTOMATIC"):
            differences.append({"field": "automatic", "expected": expected["automatic"], "actual": actual.get("reviewStatus")})
    if "movements" in expected:
        fields = ("bookedOn", "description", "amount", "direction")
        wanted = Counter(tuple(normalized(row.get(k)) for k in fields) for row in expected["movements"])
        actual = Counter(tuple(normalized(row.get(k)) for k in fields) for row in records.get("movements", []))
        checked += sum(wanted.values())
        correct += sum((wanted & actual).values())
        if wanted != actual:
            differences.append({"field": "movements", "missing": list((wanted - actual).elements()), "extra": list((actual - wanted).elements())})
            silent += sum(1 for row in records.get("movements", []) if row.get("reviewStatus") != "OPEN" and tuple(normalized(row.get(k)) for k in fields) not in wanted)
            if any(row.get("reviewStatus") != "OPEN" for row in records.get("movements", [])):
                silent += sum((wanted - actual).values())
    return {"checked": checked, "correct": correct, "silentFinancialErrors": silent, "differences": differences}


class Client:
    """Authenticated same-origin client using the application's actual session/CSRF contract."""

    def __init__(self, base: str, username: str, password: str):
        self.base = base.rstrip("/") + "/api/v1"
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.call("GET", "/csrf")
        self.call("POST", "/session", urllib.parse.urlencode({"username": username, "password": password}).encode(), "application/x-www-form-urlencoded")
        self.call("GET", "/csrf")

    def call(self, method, path, body=None, content_type="application/json"):
        """Read or write a resource without printing credentials or source document bytes."""
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
        token = next((c.value for c in self.cookies if c.name == "XSRF-TOKEN"), "")
        request = urllib.request.Request(self.base + path, body, {"Content-Type": content_type, "X-XSRF-TOKEN": token}, method=method)
        with self.opener.open(request, timeout=60) as response:
            data = response.read()
            return json.loads(data) if data else None

    def upload(self, path, kind, account):
        """Upload one corpus case through the public multipart API."""
        boundary = "conta-" + uuid.uuid4().hex
        parts = []
        for key, value in (("kind", kind), ("accountId", account)):
            if value:
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        name = path.name.replace('"', "_").replace("\n", "_").replace("\r", "_")
        parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode(), path.read_bytes(), f"\r\n--{boundary}--\r\n".encode()])
        return self.call("POST", "/documents", b"".join(parts), f"multipart/form-data; boundary={boundary}")[0]


def main():
    """Import an explicit corpus, wait for results and write a reproducible precision report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--url", default="http://localhost:8081")
    parser.add_argument("--username", default="gestor")
    parser.add_argument("--account-id")
    parser.add_argument("--wait-seconds", type=int, default=600)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    password = os.environ.get("ADMIN_PASSWORD")
    if not password:
        parser.error("Define ADMIN_PASSWORD sem a colocar na linha de comando")
    manifest = json.loads(args.manifest.read_text())
    client = Client(args.url, args.username, password)
    if len(manifest["cases"]) > 500:
        parser.error("Usa lotes de até 500 casos por avaliação")
    cases = []
    for case in manifest["cases"]:
        start = time.monotonic()
        doc = client.upload(args.manifest.parent / case["file"], case["kind"], args.account_id if case["kind"] == "STATEMENT" else None)
        if any(c["documentId"] == doc["id"] for c in cases):
            parser.error("O corpus contém documentos repetidos")
        cases.append({"case": case, "documentId": doc["id"], "started": start})
    deadline = time.monotonic() + args.wait_seconds
    while time.monotonic() < deadline:
        pending = False
        jobs = client.call("GET", "/jobs?limit=10000")
        allocations = client.call("GET", "/allocations")
        for case in cases:
            records = client.call("GET", f'/documents/{case["documentId"]}/records')
            case["records"] = records
            targets = {case["documentId"]} | {r["id"] for group in records.values() for r in group}
            targets |= {a["id"] for a in allocations if a["invoiceId"] in targets or a["movementId"] in targets}
            case["pending"] = any(j["targetId"] in targets and j["state"] in {"QUEUED", "WAITING", "RUNNING"} for j in jobs)
            case["failedJobs"] = [j for j in jobs if j["targetId"] in targets and j["state"] == "FAILED"]
            if not case["pending"] and "duration" not in case:
                case["duration"] = time.monotonic() - case["started"]
            pending |= case["pending"]
        if not pending:
            break
        time.sleep(2)
    scored = [{"name": c["case"]["name"], "documentId": c["documentId"], "pending": c.get("pending", True), "failedJobs": c.get("failedJobs", []), "durationSeconds": c.get("duration"), **score(c["case"]["expected"], c.get("records", {}))} for c in cases]
    checked = sum(c["checked"] for c in scored)
    silent = sum(c["silentFinancialErrors"] for c in scored)
    by_name = {c["case"]["name"]: c for c in cases}
    expected_matches = set()
    for match in manifest.get("expectedAllocations", []):
        invoice = by_name[match["invoice"]]["records"]["invoices"][0]
        movement = by_name[match["statement"]]["records"]["movements"][match["movementIndex"]]
        expected_matches.add((invoice["id"], movement["id"], str(Decimal(match["amount"]).quantize(Decimal("0.01")))))
    invoice_ids = {i["id"] for c in cases for i in c.get("records", {}).get("invoices", [])}
    actual_matches = {(a["invoiceId"], a["movementId"], a["amount"]) for a in client.call("GET", "/allocations") if a["invoiceId"] in invoice_ids and a["origin"] == "RULES"}
    wrong_matches = actual_matches - expected_matches
    durations = [c["durationSeconds"] for c in scored if c["durationSeconds"] is not None]
    unmet_automation = sum(1 for c in scored for d in c["differences"] if d["field"] == "automatic" and d["expected"])
    report = {"cases": scored, "fieldAccuracy": sum(c["correct"] for c in scored) / checked if checked else None, "silentFinancialErrors": silent,
              "incorrectAutomaticMatches": [list(m) for m in wrong_matches], "missingAutomaticMatches": [list(m) for m in expected_matches - actual_matches],
              "durationP50Seconds": statistics.median(durations) if durations else None,
              "durationP95Seconds": statistics.quantiles(durations, n=100, method="inclusive")[94] if len(durations) > 1 else durations[0] if durations else None,
              "acceptancePassed": silent == 0 and not wrong_matches and unmet_automation == 0 and not any(c["pending"] or c["failedJobs"] for c in scored)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f'{len(scored)} casos; erros financeiros silenciosos: {silent}; relatório: {args.output}')
    raise SystemExit(0 if report["acceptancePassed"] else 1)


if __name__ == "__main__":
    main()
