# Shared Ollama client

Reusable library for the chat, brochure and persona projects. It has no server,
application entry point or `application.yml`.

The `com.example.ollama.client` package contains:

- `TextGenerationClient` and `TextResponseFormat`: provider-independent text generation contract.
- `LocalOllamaTextGenerationClient`: OpenAI SDK adapter for local Ollama.
- `LocalOllamaConfiguration`: Spring configuration imported explicitly by chat and brochure.
- `LocalOllamaProperties`: settings reused by those applications and the persona adapter.

The existing `chat.local-ollama` property prefix is retained. Each consuming
application owns its configuration values; the library does not load other projects.
Outsmart retains its own specialized model adapter and settings.

From the repository root, build a consuming project and the library together:

```sh
./mvnw -pl chat -am verify
```

To run Maven directly in a consuming project directory, first install the library
and its parent once with `./mvnw -pl shared/ollama-client -am install`. After that,
commands such as `../mvnw spring-boot:run` work from `chat/`, `personas/` or `brochure/`.
