"""HTTP contract tests avoid downloading ML models while checking conversion boundaries."""

from fastapi.testclient import TestClient
import app


def test_health():
    assert TestClient(app.app).get("/health").json() == {"status": "ok"}


def test_non_pdf_rejected():
    response = TestClient(app.app).post("/convert", files={"file": ("fake.pdf", b"not a pdf")})
    assert response.status_code == 422


def test_upload_limit(monkeypatch):
    monkeypatch.setattr(app, "MAX_BYTES", 5)
    response = TestClient(app.app).post("/convert", files={"file": ("source.pdf", b"%PDF-1.7\n")})
    assert response.status_code == 413


def test_response_and_temporary_cleanup(monkeypatch):
    captured = []

    def convert(path):
        captured.append(path)
        return {"text": "Total: 12,00", "pages": 1, "page_items": [{"page": 1, "text": "Total: 12,00"}], "tables": [], "engine": "test", "engine_version": "1"}

    monkeypatch.setattr(app, "convert_pdf", convert)
    response = TestClient(app.app).post("/convert", files={"file": ("source.pdf", b"%PDF-1.7\n")})
    assert response.status_code == 200
    assert response.json()["text"] == "Total: 12,00"
    assert not captured[0].exists()
