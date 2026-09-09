# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Livia is a GraphQL backend powering the **Cosmofy** astronomy platform. It provides unified data access for planetary/universe information, astronomy pictures, natural events, aurora predictions, and curated articles across iOS/iPadOS, watchOS, tvOS, macOS, visionOS, and web platforms.

**Tech Stack**: Java 21, Spring Boot 3.5, Netflix DGS GraphQL, MongoDB Atlas, OpenAI, systemd

**Production Endpoints**:
- Global DNS: `https://livia.arryan.xyz/graphql`
- GraphiQL Playground: `https://livia.arryan.xyz/graphiql`
- Status Page: `https://status.cosmofy.arryan.xyz`

## Commands

```bash
# Run the application locally
./gradlew bootRun

# Build the project
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "xyz.arryan.livia.SomeTest"

# Generate GraphQL types from schema
./gradlew generateJava

```

### Environment Variables
Required in `.env` or system environment:
- `OPENAI_API_KEY` - For AI-generated content
- `LIVIA_REGION` - Server region identifier (for example, `dev` or `london`)
- `WEATHERKIT_KEY_ID`, `WEATHERKIT_TEAM_ID`, `WEATHERKIT_SERVICE_ID`, `WEATHERKIT_PRIVATE_KEY` - For Aurora astronomy data via Apple WeatherKit

Optional:
- `ACCEPTED_PASSPHRASES` - For API key generation endpoint
- `LITELLM_BASE_URL`, `LITELLM_MASTER_KEY` - For LiteLLM proxy
- `APOD_SERVICE_BASE_URL`, `APOD_CONNECT_TIMEOUT`, `APOD_REQUEST_TIMEOUT`, `APOD_SEARCH_REQUEST_TIMEOUT`, `APOD_MAX_ATTEMPTS`, `APOD_RETRY_BACKOFF` - APOD REST client overrides
- `NEWS_SERVICE_BASE_URL`, `NEWS_CONNECT_TIMEOUT`, `NEWS_REQUEST_TIMEOUT`, `NEWS_MAX_ATTEMPTS`, `NEWS_RETRY_BACKOFF` - News REST client overrides
- `ARTICLES_SERVICE_URL`, `ARTICLES_CONNECT_TIMEOUT`, `ARTICLES_REQUEST_TIMEOUT`, `ARTICLES_MAX_ATTEMPTS`, `ARTICLES_RETRY_BACKOFF` - Articles REST client overrides
- `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_PROPAGATORS` - OpenTelemetry Java-agent configuration

## Architecture

### GraphQL Schema-First Design
Schema files are in `src/main/resources/schema/`:
- `schema.graphqls` - Main schema with Query root, APOD, News, Picture, Article, Event, Planet, and Aurora types
- `universe.graphqls` - Hierarchical universe structure with enums and nested types

Netflix DGS Codegen generates Java types into `build/generated/.../xyz.arryan.livia.codegen` package. Run `./gradlew generateJava` after schema changes.

Livia exposes one ordinary GraphQL schema. It is not an Apollo subgraph and has no router or composition step. APOD, News, and Articles are REST data sources called by normal DGS resolvers. The `livia` Stellate service in the `cosmofy` organization is an optional edge/cache layer and does not require Federation. The Java origin is directly reachable, and existing app endpoints that use `livia.arryan.xyz` or `prod3.livia.arryan.xyz` bypass Stellate.

The News REST contract has no exact-ID endpoint. Do not add a resolver that scans `/news`, a direct Spaceflight News fallback, Java-side News Redis access, or GraphQL-side News caching. `Query.news` is deliberately non-cacheable in Stellate.

The Articles microservice owns `articles.json`, deterministic IDs, validation, Redis page caching, and rate limiting. Livia exposes only the original `articles` query and reads every microservice page to return the complete catalog expected by the app. Do not restore a Mongo/file fallback or connect Livia to the Articles Redis instance.

### Data Fetchers (Resolvers)
Located in `src/main/java/xyz/arryan/livia/datafetchers/`:

| Fetcher | Data Source | Cache Strategy |
|---------|-------------|----------------|
| **UniverseDataFetcher** | MongoDB `universe` collection | Instance-level cache |
| **DeprecatedPlanetsDataFetcher** | `planets.json` (legacy) | Static file |
| **PictureDataFetcher** | NASA APOD API + OpenAI | MongoDB persistent |
| **ApodDataFetcher** | Internal APOD microservice | Service-owned Redis/Turso cache |
| **NewsDataFetcher** | Internal News microservice | Service-owned Redis cache; no Livia/Stellate cache |
| **ArticlesDataFetcher** | Internal Articles microservice | Service-owned Redis + Stellate edge cache |
| **EventsDataFetcher** | NASA EONET API | In-memory |
| **AuroraDataFetcher** | NOAA SWPC, WeatherKit, ML API | ConcurrentHashMap with TTLs |

APOD has one root entry point: `apod(date: Date): Apod!`. Its original scalar
picture fields remain available but deprecated for existing clients. New clients
use `apod.today`, `apod.byDate(date:)`, `apod.search(query:, limit:)`, and
`apod.similar(date:, limit:)`. Similarity is nullable to isolate upstream outages
and uses the REST contract in `docs/apod-similarity-contract.md`.
Search results contain picture fields directly alongside relevance metadata.
Do not restore the unused root `searchApods` field. Namespace-only requests must
not fetch the legacy picture; preserve selection behavior for aliases, fragments,
and skip/include directives.

### Universe Hierarchy
The `universe` query provides a hierarchical structure stored as a single nested MongoDB document (`_id: "observable-universe"`):
```
Universe > Supercluster > GalaxyCluster > Galaxy > StarSystem > Star/Planets/DwarfPlanets > Satellites
```

Each level supports name filtering via `names: [String!]` argument.

### Aurora API Architecture
The `aurora(lat, lon)` query aggregates multiple external data sources:
- **NOAA SWPC**: KP index forecast, solar wind (DSCOVR 2-hour), X-ray flares, OVATION aurora oval images
- **NASA SDO**: Sun imagery URLs (AIA wavelengths)
- **WeatherKit**: Sunrise/sunset, moon phase via JWT-authenticated Apple API
- **Aurora ML API**: ML predictions at `aurora.arryan.xyz`
- **Nearby Predictions**: ~315 points in concentric rings (50mi spacing, 250mi radius)

Uses selective field fetching via `DataFetchingEnvironment.getSelectionSet()` - only fetches data for GraphQL fields actually requested.

### Key Patterns

**DGS Annotations**:
- `@DgsComponent` - Marks class as containing data fetchers
- `@DgsQuery` - Root query resolver
- `@DgsData(parentType, field)` - Nested field resolver
- `@InputArgument` - GraphQL argument injection

**Document Navigation**: UniverseDataFetcher uses recursive `findXxxDoc()` methods to traverse the nested MongoDB document structure.

### Key Configuration
- **Port**: 2259
- **Virtual Threads**: Enabled (`dgs.graphql.virtualthreads.enabled=true`)
- **Database**: MongoDB Atlas (`cosmofy` database; `universe` hierarchy and legacy `picture` persistence only)
- **Logging**: one-line Logstash JSON on stdout/journald with request/trace MDC correlation

### Deployment
GitHub Actions validates the Java build, then the successful build workflow deploys that exact commit to the single Oracle Cloud London instance over Tailscale/SSH and restarts the `livia.service` systemd unit:
- `build.yml` - Java build and test suite
- `deploy-oracle.yml` - Oracle London deployment (`ubuntu@oracle`, `/home/ubuntu/livia-oracle`)
