# AGENTS.md — navikt/ao-oppfolgingskontor

## Repository overview

`ao-oppfolgingskontor` håndterer kontortilhørighet for arbeidsrettet oppfølging.
Tjenesten er Kotlin/Ktor-basert og bruker PostgreSQL (Exposed), Kafka, GraphQL og NAIS.

## Build and test

```bash
./gradlew test
./gradlew build
```

## Tech and runtime

- Language: Kotlin
- Framework: Ktor
- JDK: 21 (Temurin)
- Build: Gradle (Kotlin DSL)
- Database: PostgreSQL + Flyway
- Messaging: Kafka

## Working agreements for AI agents

### Always

- Følg eksisterende mønstre i repoet før du introduserer nye.
- Kjør minst `./gradlew test` etter kodeendringer.
- Bruk Flyway for databaseskjem-endringer.
- Hold endringer små og fokuserte, spesielt i domenelogikk.

### Ask first

- Endringer i auth/oppsett mot Azure AD, TokenX eller NAIS-manifester.
- Endringer i Kafka topic-kontrakter eller event-format.
- Endringer i produksjonskritiske workflows.

### Never

- Commit secrets eller tokens.
- Omgå sikkerhetskontroller.
- Auto-merge PR-er uten eksplisitt godkjenning.

