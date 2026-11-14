---
name: ub-java
description: Guide Java code generation, edits, refactoring, and review, including Spring Boot controllers, services, persistence, configuration, and tests. Use for Java source and Maven runtime/build decisions; combine with ub-quality. Preserve the project's supported Java and framework versions. Do not use as the primary skill for frontend or Python implementation.
---

# UB Java

## Automatic application

Apply with `ub-quality` whenever repository instructions route Java work here.
Inspect the target module's POM, source, configuration, and tests first. An
aggregated Maven module need not inherit the root parent; preserve independent
builds and do not assume one plugin configuration covers every module.

Use supported Java/Spring APIs. A coding request does not authorize a framework
upgrade, a repository-wide refactor, or installation of quality tooling.

## Methods and values

- Apply the shared size, complexity, naming, and extraction defaults before
  finishing. Keep use-case orchestration readable at one abstraction level.
- Use named private methods for meaningful local steps and focused collaborators
  for responsibilities with their own state, dependencies, or lifecycle.
  Avoid generic utility classes and dependency bags.
- Keep streams/lambdas short and side effects explicit. Prefer a clear loop to
  a chain that mixes nested mapping, mutation, exception handling, and I/O.
- Prefer typed DTOs, records, or domain values to heterogeneous maps when a
  contract is known. Keep a dynamic map at the boundary that actually needs it.
- Prefer immutable values; model units and precision explicitly. Handle nulls
  deliberately and preserve public absence semantics during refactoring.
- Extract meaningful domain constants and fixed model instructions using the
  repository's prompt catalog/resource rules. Do not externalize dynamic input
  as instructions or change assembled prompt text during extraction.

## Spring boundaries

- Organize around features/domains while respecting existing package structure.
- Use constructor injection for required collaborators and final dependency
  fields. Preserve legitimate framework-managed persistence/configuration
  injection; do not introduce field injection for new required services.
- Controllers translate HTTP input/output and delegate use cases. Keep business
  policy and new direct repository/database access out of controllers.
- Services/domain collaborators must not depend on controllers or HTTP request
  and response types. Shared client libraries must not depend on applications.
- Keep transaction ownership explicit around a coherent state change. Account
  for proxy/self-invocation behavior when extracting transactional methods;
  preserve locking, ordering, rollback, and persistence semantics.
- Separate slow external calls and retry policy from transactional mutations
  when the actual consistency requirements allow it. Do not change an existing
  transaction boundary casually to shorten a method.
- Validate request contracts at entry points. Expose intentional DTOs rather
  than accidentally serializing persistence entities or internal state.
- Use existing typed configuration and error handling. Preserve exception
  causes and useful diagnostic context without logging sensitive payloads.

## Verification and completion

Run existing checks for the touched module and behavior yourself. Use focused
JUnit tests for policy and appropriate existing integration tests for database,
HTTP, serialization, or transaction boundaries. Assert observable outcomes;
avoid tests that merely verify private helper calls.

Recheck dependency direction, resource cleanup, error paths, prompt composition,
and changed public contracts. Explain material exceptions and blocked checks
concisely. This is the normal self-check, not an invocation of `ub-review`.

## Sources

Consult the documentation matching the detected framework/runtime for
version-sensitive decisions:

- [Spring dependency injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)
- [Spring declarative transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html)
- [Java documentation](https://docs.oracle.com/en/java/)
