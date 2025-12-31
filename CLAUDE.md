# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Livia is a GraphQL backend powering the **Cosmofy** astronomy platform. It provides unified data access for planetary/universe information, astronomy pictures, natural events, aurora predictions, and curated articles across iOS/iPadOS, watchOS, tvOS, macOS, visionOS, and web platforms.

**Tech Stack**: Java 21, Spring Boot 3.4, Netflix DGS (GraphQL), MongoDB Atlas, OpenAI, Docker

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
- `LIVIA_REGION` - Server region identifier (e.g., "dev", "prod1")
- `WEATHERKIT_KEY_ID`, `WEATHERKIT_TEAM_ID`, `WEATHERKIT_SERVICE_ID`, `WEATHERKIT_PRIVATE_KEY` - For Aurora astronomy data via Apple WeatherKit

Optional:
- `ACCEPTED_PASSPHRASES` - For API key generation endpoint
- `LITELLM_BASE_URL`, `LITELLM_MASTER_KEY` - For LiteLLM proxy

## Architecture

### GraphQL Schema-First Design
Schema files are in `src/main/resources/schema/`:
- `schema.graphqls` - Main schema with Query root, Picture, Article, Event, Planet, Aurora types
- `universe.graphqls` - Hierarchical universe structure with enums and nested types

Netflix DGS Codegen generates Java types into `build/generated/.../xyz.arryan.livia.codegen` package. Run `./gradlew generateJava` after schema changes.

### Data Fetchers (Resolvers)
Located in `src/main/java/xyz/arryan/livia/datafetchers/`:

| Fetcher | Data Source | Cache Strategy |
|---------|-------------|----------------|
| **UniverseDataFetcher** | MongoDB `universe` collection | Instance-level cache |
| **DeprecatedPlanetsDataFetcher** | `planets.json` (legacy) | Static file |
| **PictureDataFetcher** | NASA APOD API + OpenAI | MongoDB persistent |
| **ArticlesDataFetcher** | `articles.json` | Static file |
| **EventsDataFetcher** | NASA EONET API | In-memory |
| **AuroraDataFetcher** | NOAA SWPC, WeatherKit, ML API | ConcurrentHashMap with TTLs |

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
- **Database**: MongoDB Atlas (`cosmofy` database, `universe` collection for hierarchy)
- **Logging**: DEBUG level for `xyz.arryan.livia` with colorized console output

### Deployment
Multi-region via GitHub Actions to GHCR (`ghcr.io/cosmofy/livia`):
- `build.yml` - Multi-arch Docker images (amd64/arm64)
- Region workflows: `deploy-livia-prod-1-raptor.yml` (US), `deploy-livia-prod-2-uksouth.yml` (UK/Oracle), `deploy-livia-prod-3-singapore.yml` (GCP)
