# AI Labs

A collection of independent AI experiments and applications. Each project owns its
source, tests, configuration and runtime. The root Maven project groups the Java
builds and provides shared dependency versions; it does not start an application.

## Projects

| Folder | Project | Default address |
| --- | --- | --- |
| [chat](chat/README.md) | REST API for local Ollama text generation | `http://localhost:8080/api/chat` |
| [accounting](accounting/README.md) | Company expenses, documents and bank reconciliation | [http://localhost:8081](http://localhost:8081) |
| [outsmart](outsmart/README.md) | Strategy game between local models | [http://localhost:8082/outsmart](http://localhost:8082/outsmart) |
| [personas](personas/README.md) | Conversations between two model personas | [http://localhost:8083/personas](http://localhost:8083/personas) |
| [brochure](brochure/README.md) | Company brochure generation from website content | `http://localhost:8084/api/brochures` |
| [shared/ollama-client](shared/ollama-client/README.md) | Reusable Ollama client library | No server |

Each application can run independently. `PORT` overrides its default port. API
paths are unchanged; Outsmart, personas and brochure now have their own ports.
Accounting retains its existing backend, frontend, document worker, scripts and
Docker setup. Its backend is aggregated by the root build but retains its own
Spring Boot parent and dependencies.

## Requirements

- Java 25 JDK.
- The included Maven wrapper; Maven is downloaded on first use.
- [Ollama](https://ollama.com/) with the desired models installed to use model-backed features.
- Accounting's additional requirements are described in [its README](accounting/README.md).

The repository's `mise.toml` selects Temurin 25. If needed, link a local installation
with `mise link java@temurin-25 /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.

## Build and run

Run these commands from the repository root. Build a project together with its
shared dependencies, then start its executable JAR:

```sh
./mvnw -pl chat -am package
ollama pull gemma3:1b
java -jar chat/target/chat-0.0.1-SNAPSHOT.jar
```

Replace `chat` with `outsmart`, `personas` or `brochure` to build and run that project.
Each project README includes its configuration and usage examples. Follow the
[accounting startup instructions](accounting/README.md) for its environment and services.

Verify the four experiments and their shared client without building accounting:

```sh
./mvnw -pl chat,outsmart,personas,brochure -am verify
```

`./mvnw verify` includes accounting's backend and database integration tests; it
does not compile accounting's Angular frontend or test its Python worker. The
existing accounting workflow handles those components separately.

## IDE

Import or reload the root `pom.xml` as a Maven project and select JDK 25. This imports
all Java projects, including `accounting/backend`, with their own source roots.
Run the appropriate main class:

| Project | Main class |
| --- | --- |
| Chat | `com.example.ollama.chat.ChatApplication` |
| Outsmart | `com.example.ollama.outsmart.OutsmartApplication` |
| Personas | `com.example.ollama.personas.PersonasApplication` |
| Brochure | `com.example.ollama.brochure.BrochureApplication` |
| Accounting | `pt.conta.AccountingApplication` |

Accounting's IDE environment and working directory remain as documented in its
README. The other applications require no database or accounting configuration.

## Continuous integration

- `java-projects.yml` verifies chat, Outsmart, personas, brochure and their shared library.
- `accounting.yml` continues to build and test accounting independently.
