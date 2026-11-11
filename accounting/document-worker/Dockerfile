FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-por tesseract-ocr-eng libglib2.0-0 libgl1 libgomp1 curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN pip install --no-cache-dir uv==0.13.6 \
    && uv sync --frozen --no-dev --no-install-project
COPY app.py ./
RUN useradd -u 10002 -m converter && mkdir -p /models && chown converter /models
USER converter
ENV PATH="/app/.venv/bin:$PATH" HF_HOME=/models/huggingface TORCH_HOME=/models/torch DOCLING_CACHE_DIR=/models/docling OCR_THREADS=2
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s CMD curl -fsS http://localhost:8090/health || exit 1
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8090","--workers","1"]
