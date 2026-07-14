# Storm Movies · Kotlin + Ktor · GraalVM native image

The [Kotlin + Ktor example](https://github.com/storm-orm/storm-example-kotlin-ktor)
compiled to a GraalVM native image: the same IMDB movie browser with search,
genres, people, statistics, and a watchlist, but the binary starts in about
0.13 seconds, runs the Flyway migration, validates every entity against the
live database schema, and serves pages. No JVM, no warmup.

## How Storm supports this without Spring

Spring applications get Storm's native-image registrations through the Spring
AOT hints in storm-spring. Ktor has no AOT phase, so storm-core ships a
GraalVM feature instead: `StormFeature` reads the compile-time type index the
metamodel processor writes to `META-INF/storm/` and registers, at image build
time, exactly what the Spring hints register:

- every Data type (entities and projections) with constructors, methods, and
  fields, including compound primary keys and inline components reached
  through constructor signatures, and the kotlinx.serialization companions;
- every repository interface as a JDK proxy shape, with the Kotlin
  `$DefaultImpls` classes for default methods;
- converters for reflective construction.

The feature activates automatically through storm-core's
`native-image.properties` whenever the jar is on the image classpath. During
the image build it reports:

```
Storm: registered 11 data types, 0 converters, and 11 repositories from the type index.
```

The Ktor plugin's repository auto-registration reads the same index at
runtime, so scanned repositories work in the native image with no
configuration at all.

## What the application itself declares

Everything Storm-related comes from the framework. The application-level
metadata in
[`META-INF/native-image/st.orm.demo/storm-imdb-ktor-graalvm/`](src/main/resources/META-INF/native-image/st.orm.demo/storm-imdb-ktor-graalvm/)
covers the rest of the stack:

| Entry | Why |
|---|---|
| `ApplicationKt` reflection | Ktor's `EngineMain` resolves the configured module function reflectively from `application.conf`. |
| View models with their kotlinx companions | Response and template shapes (`SearchSuggestions`, `HomeView`, ...) are serialized through runtime serializer lookup and read by Thymeleaf expressions. |
| Thymeleaf/OGNL-facing JDK types | Template expressions invoke methods reflectively on `#numbers`-style utility objects and plain JDK value and collection types. |
| ktor-network selector fields | The CIO engine initializes atomic field updaters over `InterestSuspensionsMap` and the socket classes. |
| Resources | `templates/`, `static/`, `db/migration/`, `application.conf`, `logback.xml`. |

Two code-level accommodations, both working identically on the JVM:

- **CIO instead of Netty**: Ktor's recommended engine for native images
  (one dependency and the `EngineMain` class in `build.gradle.kts`).
- **Explicit Flyway resources**: native images cannot scan the classpath, so
  the migration hook hands Flyway its migration files through a small
  [`FlywayResources`](src/main/kotlin/st/orm/demo/imdb/FlywayResources.kt)
  provider, the same mechanism Spring Boot uses for its Flyway integration.

## Stack

- Kotlin 2.2 / Java 21, Ktor 3.2 (CIO, Thymeleaf, ContentNegotiation, ktor-server-di)
- Storm ORM (`storm-ktor`) with the KSP metamodel generator and the Storm
  compiler plugin
- **Oracle GraalVM for JDK 23+** (tested with GraalVM for JDK 25)
- PostgreSQL 17 (Docker Compose) with Flyway migrations, run explicitly at startup
- kotlinx.serialization for JSON APIs and cache values; Jackson for parsing
  external APIs
- JUnit 5 + `storm-test` on H2 for repository tests, Playwright for interface tests

## Running the application

Prerequisites: JDK 21, Docker, and a GraalVM for JDK 23+ for the native build.

```bash
# 1. Start PostgreSQL. This is the same Compose stack as the other Storm
#    examples, so a database imported by any of them is shared.
docker compose up -d

# 2. Compile the native image (about 2 minutes)
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile

# 3. Run the binary. On an empty database the first start runs the Flyway
#    migration and streams the IMDB dataset in natively (the ~1.2 GB of
#    dataset files are downloaded once and cached in ./data); afterwards
#    the import is skipped.
./build/native/nativeCompile/storm-imdb-ktor-graalvm

# 4. Open the app
open http://localhost:8080
```

The application also still runs on the JVM with `./gradlew run`.

Measured on an Apple M-series MacBook: the binary is a 97 MB self-contained
executable that reports `Application started in 0.129 seconds`, including the
Flyway check and Storm validating all 11 entities against the live schema.

## Testing

```bash
./gradlew test
```

Repository tests run on an in-memory H2 database via `@StormTest`, so no
Docker is required.

The Playwright interface tests run against a live application, which can be
the native binary:

```bash
./gradlew installPlaywrightBrowsers                    # once
./build/native/nativeCompile/storm-imdb-ktor-graalvm    # in one terminal
./gradlew e2eTest                                      # in another
```

## Project layout

```
src/main/kotlin/st/orm/demo/imdb/
├── Application.kt      Ktor module: Storm plugin, Thymeleaf, DI, routing
├── FlywayResources.kt  Explicit Flyway resources (native images cannot scan)
├── Dependencies.kt     Service wiring via ktor-server-di
├── model/              Storm entities (@PK, @FK) and projections
├── repository/         EntityRepository interfaces with QueryBuilder queries
├── service/            Business logic in suspend `transaction { }` blocks,
│                       plus the streaming IMDB importer
├── web/                Page routes and REST routes (/api/**)
└── serialization/      kotlinx.serialization support
src/main/resources/
├── META-INF/native-image/…/   Application-level reachability metadata
├── db/migration/               Flyway schema (V1__create_schema.sql)
├── templates/                  Thymeleaf views
└── static/                     CSS, JS, images
```
