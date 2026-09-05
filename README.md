# Livia

Livia is Cosmofy's public GraphQL API for astronomy content and live space-weather data. It runs on Java 21 with Spring Boot and Netflix DGS.

Livia exposes one ordinary GraphQL schema. The downstream APOD, News, and Articles applications are REST microservices, not GraphQL subgraphs. A Stellate service provides an optional edge and caching layer; it does not make Livia a federated graph.

## Production

- Stellate edge GraphQL: `https://livia.stellate.sh`
- Java origin GraphQL: `https://livia.arryan.xyz/graphql`
- GraphiQL: [https://livia.arryan.xyz/graphiql](https://livia.arryan.xyz/graphiql)
- Health: [https://livia.arryan.xyz/health](https://livia.arryan.xyz/health)
- Status: [https://status.cosmofy.arryan.xyz](https://status.cosmofy.arryan.xyz)
- Runtime: one Oracle Cloud instance in London, reached internally as the Tailscale host `oracle`
- Process: `livia.service` under systemd

The Stellate request path is:

```text
Cosmofy clients
  -> Stellate
    -> Livia GraphQL on Oracle London
      -> APOD, News, and Articles REST services
      -> MongoDB for Universe and legacy Picture data
      -> NASA, NOAA, WeatherKit, and the Aurora prediction API
```

There is no Apollo Router, multi-subgraph composition step, `_service` field, or `_entities` field. DGS's Federation schema transformation is explicitly disabled.

The Java origin remains directly reachable. Clients configured with `livia.arryan.xyz`, `prod3.livia.arryan.xyz`, or another origin hostname bypass Stellate. At the time of writing, the separate Cosmofy app repository still contains those direct endpoints, so do not assume all app traffic receives Stellate caching.

## API modules

| GraphQL fields | Source | Cache/storage ownership |
| --- | --- | --- |
| `apod`, `searchApods` | Cosmofy APOD REST service | APOD service owns Redis/Turso; Stellate caches `apod` for 5 minutes and does not cache search |
| `news` | Cosmofy News REST service | News service owns Redis; News is non-cacheable in Stellate |
| `articles` | Cosmofy Articles REST service | Articles service owns its JSON catalog, deterministic UUIDs, Redis page cache, and rate limiting; Stellate caches article data for 6 hours |
| `universe` and nested hierarchy | MongoDB `universe`, document `_id=observable-universe` | Loaded into the Livia instance cache; Stellate caches hierarchy data for 1 day |
| `planets` | Bundled `planets.json` compatibility dataset | Static process data; Stellate caches planetary data for 1 day |
| `picture` | NASA APOD plus OpenAI summaries | Legacy MongoDB `pictures` persistence; Stellate caches for 48 hours |
| `events` | NASA EONET | Stellate caches event data for 4 hours |
| `aurora` | NOAA SWPC, WeatherKit, Aurora prediction API, bundled webcams | Short in-process caches plus a 5-minute Stellate edge policy |
| `apiKey` | LiteLLM key API | Never edge-cached |
| `server`, `time` | Livia runtime | Never edge-cached |

The authoritative edge policy is [stellate.ts](./stellate.ts). It targets the Stellate service named `livia` in the `cosmofy` organization.

## GraphQL examples

### APOD

```graphql
query TodaysApod {
  apod {
    date
    title
    explanation
    mediaType
    url
    hdUrl
    credit
    copyright
  }
}

query HistoricalApod {
  apod(date: "2024-02-29") {
    date
    title
    url
  }
}

query SearchApods {
  searchApods(query: "spiral galaxy", limit: 5) {
    query
    searchMode
    results {
      apod { date title mediaType url }
      relevanceScore
      matchTypes
    }
  }
}
```

### News

```graphql
query LatestNews($limit: Int!, $offset: Int!, $ordering: NewsOrdering!) {
  news(limit: $limit, offset: $offset, ordering: $ordering) {
    totalCount
    limit
    offset
    hasNextPage
    articles {
      id
      title
      summary
      url
      imageUrl
      newsSite
      authors
      publishedAt
      updatedAt
    }
  }
}
```

News exact-ID lookup is intentionally absent because its REST service currently exposes only the collection endpoint. Livia does not scan the complete feed to emulate one.

### Articles

The `articles: [Article]` field returns the complete catalog expected by the app. Livia reads every page from the Articles REST service in batches of 100 and combines them into one GraphQL list; it does not read articles from MongoDB or keep a second Java-side article cache.

```graphql
query Articles {
  articles {
    id
    month
    year
    title
    subtitle
    url
    source
    banner { image designer }
    authors { name title image }
  }
}
```

### Universe and live data

```graphql
query AstronomyOverview($lat: Float!, $lon: Float!) {
  planets { name moons rings }
  universe {
    name
    superclusters {
      name
      galaxyClusters {
        name
        galaxies { name type }
      }
    }
  }
  events(daysInput: 14) { id title categories { id title } }
  aurora(lat: $lat, lon: $lon) {
    prediction { probability }
    spaceWeather { current { kp } }
    astronomy { moon { phase } }
  }
}
```

Use GraphiQL or schema introspection for the complete nested Universe and Aurora types.

## Development

Requirements:

- JDK 21
- Network access to the configured REST services and MongoDB when exercising their fields
- Optional credentials for legacy Picture, WeatherKit astronomy, and LiteLLM features

Load the example environment and start Livia:

```bash
cp .env.example .env
set -a
. ./.env
set +a
./gradlew bootRun
```

The local server listens on port `2259`:

```bash
curl http://127.0.0.1:2259/health
```

Build and test:

```bash
./gradlew build --no-daemon
./gradlew test --no-daemon
```

DGS generates Java schema types under `build/generated/` from:

- `src/main/resources/schema/schema.graphqls`
- `src/main/resources/schema/universe.graphqls`

## Configuration

Copy `.env.example` for the complete documented defaults. The main groups are:

| Variables | Purpose |
| --- | --- |
| `APOD_SERVICE_BASE_URL`, `APOD_*TIMEOUT`, `APOD_MAX_ATTEMPTS`, `APOD_RETRY_BACKOFF` | APOD REST client endpoint and bounded resilience policy |
| `NEWS_SERVICE_BASE_URL`, `NEWS_*TIMEOUT`, `NEWS_MAX_ATTEMPTS`, `NEWS_RETRY_BACKOFF` | News REST client endpoint and bounded resilience policy |
| `ARTICLES_SERVICE_URL`, `ARTICLES_*TIMEOUT`, `ARTICLES_MAX_ATTEMPTS`, `ARTICLES_RETRY_BACKOFF` | Articles REST client endpoint and bounded resilience policy |
| `NASA_API_KEY`, `OPENAI_API_KEY` | Legacy `picture` generation on a cache miss |
| `WEATHERKIT_KEY_ID`, `WEATHERKIT_TEAM_ID`, `WEATHERKIT_SERVICE_ID`, `WEATHERKIT_PRIVATE_KEY` | Optional WeatherKit astronomy fields |
| `AURORA_ML_API_URL` | Optional override for the Aurora prediction service |
| `LITELLM_BASE_URL`, `LITELLM_MASTER_KEY`, `ACCEPTED_PASSPHRASES` | Virtual API-key generation |
| `LIVIA_REGION` | Value returned by `server` and included in request logs |
| `OTEL_*` | OpenTelemetry Java-agent export and propagation settings |

The three internal REST services currently require no authorization header. Their production URLs are non-secret defaults in `application.properties` and `.env.example`.

## Resilience and observability

The APOD, News, and Articles clients use validated base URLs, bounded connect/request timeouts, and at most one retry by default. Validation failures and rate limits are not blindly retried. Structured upstream failures become safe GraphQL errors without exposing Java stack traces or internal service messages.

Livia validates or creates `x-request-id`, emits structured request/resolver logs, and forwards `x-request-id`, `traceparent`, and `tracestate` to its REST services. Cache and rate-limit response headers are recorded as telemetry rather than GraphQL business fields.

Trace export requires the OpenTelemetry Java agent and a private collector. The recommended collector endpoint is `http://127.0.0.1:4318` using OTLP/HTTP. The CI/CD workflow does not install that infrastructure, so verify the agent and collector independently on production.

## Deployment

Production deploys are intentionally single-instance:

1. A push to `main` runs the Java build and test suite in `.github/workflows/build.yml`.
2. Only a successful build triggers `.github/workflows/deploy-oracle.yml`.
3. The deploy job connects to the Tailscale host `oracle`, verifies the exact build SHA, and fast-forwards `/home/ubuntu/livia-oracle`.
4. It restarts `livia.service`, waits for `/health`, and runs acceptance queries for the existing API, APOD, News, paginated Articles, and exact Article lookup.

Production configuration lives in `/home/ubuntu/livia-oracle/.env`, loaded by systemd. Deployment preserves that file and the host's untracked `gradle.properties`.

The Java deployment workflow does not publish Stellate configuration. After reviewing a dry run, publish edge changes separately with an authenticated CLI session:

```bash
npx --yes stellate@3.2.28 push --org cosmofy --dry
npx --yes stellate@3.2.28 push --org cosmofy
```

## Repository layout

```text
src/main/resources/schema/       GraphQL schema
src/main/java/.../datafetchers/  DGS query and field resolvers
src/main/java/.../clients/       Typed REST clients
src/main/java/.../services/      Validation and orchestration
src/main/java/.../mappers/       REST-to-GraphQL mapping
src/test/                        Unit and HTTP/GraphQL integration tests
.github/workflows/               Build and Oracle deployment
stellate.ts                      Edge cache policy
```

## License

[Apache License 2.0](./LICENSE)
