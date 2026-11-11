"""Reproducible private 2016 comparison; originals and initial application results stay immutable."""

from __future__ import annotations
import argparse, collections, datetime as dt, decimal, hashlib, json, os, time, unicodedata, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from evaluate import Client

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
MONTHS = [
    "Janeiro",
    "Fevereiro",
    "Março",
    "Abril",
    "Maio",
    "Junho",
    "Julho",
    "Agosto",
    "Setembro",
    "Outubro",
    "Novembro",
    "Dezembro",
]


def money(value):
    """Round Excel binary storage residue at its displayed cent precision, retaining raw elsewhere."""
    return (
        None
        if value is None
        else str(
            decimal.Decimal(str(value)).quantize(
                decimal.Decimal(".01"), rounding=decimal.ROUND_HALF_UP
            )
        )
    )


def prepare(workbook: Path, archive: Path, output: Path):
    """Create an independent XML reference and select only expenses/statements from Q1."""
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    if (output / "reference.json").exists():
        raise FileExistsError(
            "Usa um diretório novo para conservar a referência e os ensaios anteriores"
        )
    rows = []
    controls = []
    with zipfile.ZipFile(workbook) as z:
        strings = [
            "".join(i.itertext()) for i in ET.fromstring(z.read("xl/sharedStrings.xml"))
        ]
        relations = {
            r.attrib["Id"]: r.attrib["Target"]
            for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        }
        for sheet in ET.fromstring(z.read("xl/workbook.xml")).find("s:sheets", NS):
            name = sheet.attrib["name"]
            target = relations[
                sheet.attrib[
                    "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
                ]
            ]
            target = target.lstrip("/") if target.startswith("/") else "xl/" + target
            cells = {}
            formulas = {}
            for cell in ET.fromstring(z.read(target)).findall(".//s:c", NS):
                value = cell.find("s:v", NS)
                formula = cell.find("s:f", NS)
                address = cell.attrib["r"]
                if value is not None and value.text is not None:
                    cells[address] = (
                        strings[int(value.text)]
                        if cell.attrib.get("t") == "s"
                        else value.text
                    )
                if formula is not None:
                    formulas[address] = formula.text
            controls.append(
                {
                    "sheet": name,
                    "opening": money(cells.get("H9")),
                    "received": money(cells.get("G66")),
                    "payment": money(cells.get("G67")),
                    "closing": money(cells.get("G68")),
                }
            )
            for r in range(14, 65):
                if not (cells.get(f"G{r}") or cells.get(f"H{r}")):
                    continue
                serial = cells.get(f"C{r}")
                date = (
                    (dt.datetime(1899, 12, 30) + dt.timedelta(days=float(serial)))
                    .date()
                    .isoformat()
                    if serial
                    else None
                )
                rows.append(
                    {
                        "sheet": name,
                        "rowNumber": r,
                        "date": date,
                        "description": cells.get(f"D{r}"),
                        "received": money(cells.get(f"G{r}")),
                        "payment": money(cells.get(f"H{r}")),
                        "balance": money(cells.get(f"I{r}")),
                        "raw": {
                            a: v
                            for a, v in cells.items()
                            if a in [f"{c}{r}" for c in "CDGHI"]
                        },
                        "formulas": {a: v for a, v in formulas.items() if a == f"I{r}"},
                    }
                )
    selected = []
    with (
        zipfile.ZipFile(archive) as z,
        zipfile.ZipFile(output / "quarter.zip", "w", zipfile.ZIP_DEFLATED) as filtered,
    ):
        for entry in z.infolist():
            name = entry.filename
            if not entry.flag_bits & 0x800:
                try:
                    name = name.encode("cp437").decode("utf8")
                except UnicodeError:
                    pass
            name = unicodedata.normalize("NFC", name)
            parts = Path(name).parts
            if (
                len(parts) < 3
                or parts[1] not in MONTHS[:3]
                or entry.is_dir()
                or not name.lower().endswith(".pdf")
            ):
                continue
            kind = (
                "STATEMENT"
                if "extrato" in parts[-1].lower()
                else "INVOICE"
                if "despesas" in [p.lower() for p in parts]
                else None
            )
            if not kind:
                continue
            if ".." in parts or Path(name).is_absolute():
                raise ValueError("Unsafe archive path")
            data = z.read(entry)
            path = output / "corpus" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            filtered.writestr(name, data)
            selected.append(
                {
                    "file": str(path.resolve()),
                    "path": name,
                    "sheet": parts[1],
                    "kind": kind,
                    "sha256": hashlib.sha256(data).hexdigest(),
                }
            )
    result = {
        "sources": {
            str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [workbook, archive]
        },
        "rows": rows,
        "controls": controls,
        "documents": selected,
    }
    (output / "reference.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2)
    )
    print(
        json.dumps(
            {
                "rows": len(rows),
                "quarterRows": sum(r["sheet"] in MONTHS[:3] for r in rows),
                "documents": len(selected),
                "controls": controls[:3],
            },
            ensure_ascii=False,
        )
    )


def snapshot(client: Client, output: Path):
    """Capture full auditable results before code or data corrections."""
    docs = client.call("GET", "/documents")
    result = {}
    for resource in [
        "history",
        "imports",
        "alerts",
        "allocations",
        "jobs?limit=10000",
        "assistant/executions",
        "balances",
    ]:
        result[resource.split("?")[0]] = client.call("GET", "/" + resource)
    result["documents"] = [
        {
            "metadata": d,
            "detail": client.call("GET", "/documents/" + d["id"]),
            "records": client.call("GET", "/documents/" + d["id"] + "/records"),
            "attempts": client.call("GET", "/documents/" + d["id"] + "/attempts"),
        }
        for d in docs
    ]
    result["capturedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
    if output.exists():
        raise FileExistsError("O snapshot já existe; usa outro nome de ensaio")
    temporary = output.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    temporary.replace(output)
    print("Snapshot:", output.name, "documents:", len(docs), flush=True)


def main():
    """Prepare, import through the public API or snapshot one isolated run."""
    p = argparse.ArgumentParser()
    p.add_argument("phase", choices=["prepare", "individual", "history", "snapshot"])
    p.add_argument("--workbook", type=Path)
    p.add_argument("--archive", type=Path)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--url")
    p.add_argument("--name", default="initial")
    p.add_argument("--wait", type=int, default=7200)
    p.add_argument("--rules-only", action="store_true")
    a = p.parse_args()
    if a.phase == "prepare":
        return prepare(a.workbook, a.archive, a.output)
    client = Client(a.url, "gestor", os.environ["ADMIN_PASSWORD"])
    if a.phase != "snapshot":
        accounts = client.call("GET", "/accounts")
        account = (
            accounts[0]
            if accounts
            else client.call("POST", "/accounts", {"name": "Conta de avaliação 2016"})
        )
        if a.phase == "individual":
            ref = json.loads((a.output / "reference.json").read_text())
            for item in ref["documents"]:
                client.upload(
                    Path(item["file"]),
                    item["kind"],
                    account["id"] if item["kind"] == "STATEMENT" else None,
                )
        else:
            client.upload(a.workbook, "HISTORY", None)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if not any(
                    j["kind"] == "EXTRACT" and j["state"] in ["QUEUED", "RUNNING"]
                    for j in client.call("GET", "/jobs?limit=10000")
                ):
                    break
                time.sleep(1)
            snapshot(client, a.output / (a.name + "-excel-only.json"))
            client.upload(a.output / "quarter.zip", "ARCHIVE", account["id"])
        deadline = time.monotonic() + a.wait
        last = None
        while time.monotonic() < deadline:
            jobs = client.call("GET", "/jobs?limit=10000")
            wanted = (
                {"EXTRACT", "IMPORT_ZIP"}
                if a.phase == "individual" or a.rules_only
                else None
            )
            counts = collections.Counter(
                j["state"] for j in jobs if wanted is None or j["kind"] in wanted
            )
            if counts != last:
                print(a.name, dict(counts), flush=True)
                last = counts
            if not any(
                j["state"] in ["QUEUED", "RUNNING", "WAITING"]
                for j in jobs
                if wanted is None or j["kind"] in wanted
            ):
                break
            time.sleep(5)
    snapshot(client, a.output / (a.name + ".json"))


if __name__ == "__main__":
    main()
