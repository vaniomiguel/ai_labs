# Company brochure generator

A standalone Java 25 / Spring Boot API that uses the shared text generation client
from `../shared/ollama-client`.

## Run

From the repository root:

```sh
./mvnw -pl brochure -am package
ollama pull gemma3:1b
java -jar brochure/target/brochure-0.0.1-SNAPSHOT.jar
```

`PORT` overrides the default port of 8084. Choose the local model with `OLLAMA_MODEL`
and its OpenAI-compatible API address with `OLLAMA_BASE_URL` (defaults:
`gemma3:1b` and `http://localhost:11434/v1`).

## API


The brochure API reads a website, selects relevant linked pages with the configured model, and returns a Markdown brochure:

```bash
curl --get http://localhost:8084/api/brochures \
  --data-urlencode 'url=https://example.com'
```

The response uses the `text/markdown` media type. A browser page needs a Markdown renderer to display its headings and paragraphs with formatting.
You can omit the URL scheme (for example, `www.example.com`); the API will use HTTPS by default.
