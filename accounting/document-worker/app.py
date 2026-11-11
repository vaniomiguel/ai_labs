"""Local text/layout conversion only. Financial decisions belong to the Java application."""

from __future__ import annotations

import asyncio
import importlib.metadata
import os
import tempfile
from functools import lru_cache
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, UploadFile
from pydantic import BaseModel

app = FastAPI(title="Conta document converter", version="0.1.0")
conversion_lock = asyncio.Lock()
MAX_BYTES = int(os.getenv("MAX_DOCUMENT_BYTES", str(256 * 1024 * 1024)))
MAX_PAGES = int(os.getenv("MAX_DOCUMENT_PAGES", "1000"))


class ConvertedDocument(BaseModel):
    """Stable response shape, independent of Docling's internal Python objects."""

    text: str
    pages: int
    page_items: list[dict[str, Any]]
    tables: list[dict[str, Any]]
    engine: str
    engine_version: str


@lru_cache(maxsize=1)
def get_converter():
    """Keep CPU model weights loaded between serial conversion jobs."""
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import PdfPipelineOptions, TesseractCliOcrOptions
    from docling.document_converter import DocumentConverter, PdfFormatOption
    from docling.datamodel.accelerator_options import AcceleratorDevice, AcceleratorOptions

    options = PdfPipelineOptions()
    options.do_ocr = True
    options.do_table_structure = True
    options.enable_remote_services = False
    options.ocr_options = TesseractCliOcrOptions(lang=["por", "eng"], force_full_page_ocr=True)
    options.accelerator_options = AcceleratorOptions(
        num_threads=int(os.getenv("OCR_THREADS", "2")), device=AcceleratorDevice.CPU
    )
    if os.getenv("DOCLING_ARTIFACTS_PATH"):
        options.artifacts_path = Path(os.environ["DOCLING_ARTIFACTS_PATH"])
    return DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=options)})


def convert_pdf(path: Path) -> dict[str, Any]:
    """Use CPU layout/table models and Portuguese/English Tesseract, without remote services."""
    result = get_converter().convert(path, max_num_pages=MAX_PAGES, max_file_size=MAX_BYTES)
    document = result.document
    page_items: dict[int, list[str]] = {}
    evidence: dict[int, list[dict[str, Any]]] = {}
    for item, _ in document.iterate_items():
        text = getattr(item, "text", None)
        if text:
            for provenance in getattr(item, "prov", []):
                page_items.setdefault(provenance.page_no, []).append(text)
                evidence.setdefault(provenance.page_no, []).append({
                    "text": text,
                    "label": str(getattr(item, "label", "text")),
                    "provenance": provenance.model_dump(mode="json"),
                })
    tables = []
    for table in document.tables:
        tables.append({"cells": table.data.model_dump(mode="json"), "provenance": [p.model_dump(mode="json") for p in table.prov]})
        if table.prov:
            page_items.setdefault(table.prov[0].page_no, []).append(table.export_to_markdown(doc=document))
    pages = [{"page": number, "text": "\n".join(page_items.get(number, [])), "items": evidence.get(number, [])} for number in sorted(document.pages)]
    return {
        "text": "\n\n".join(page["text"] for page in pages),
        "pages": len(document.pages),
        "page_items": pages,
        "tables": tables,
        "engine": "docling+tesseract-por-eng",
        "engine_version": importlib.metadata.version("docling"),
    }


@app.get("/health")
def health() -> dict[str, str]:
    """Report service availability without loading expensive models."""
    return {"status": "ok"}


@app.post("/convert", response_model=ConvertedDocument)
async def convert(file: UploadFile) -> dict[str, Any]:
    """Bound uploads and serialize CPU-heavy conversion; temporary originals are always removed."""
    async with conversion_lock:
        with tempfile.TemporaryDirectory(prefix="conta-ocr-") as directory:
            path = Path(directory) / "source.pdf"
            size = 0
            with path.open("wb") as output:
                while chunk := await file.read(65536):
                    size += len(chunk)
                    if size > MAX_BYTES:
                        raise HTTPException(413, "Documento demasiado grande")
                    output.write(chunk)
            with path.open("rb") as source:
                signature = source.read(8)
            if not signature.startswith(b"%PDF-"):
                raise HTTPException(422, "O documento não é um PDF válido")
            try:
                return await asyncio.to_thread(convert_pdf, path)
            except Exception as error:
                raise HTTPException(422, f"Conversão não concluída: {type(error).__name__}") from error
            finally:
                await file.close()
