# Livia


A scalable, AI-augmented GraphQL backend powering the **Cosmofy** astronomy platform across iOS/iPadOS, watchOS, tvOS, macOS, visionOS, and web.

Production requests flow from clients through Stellate to this Livia subgraph on Oracle London. Livia then calls its internal REST microservices.

**Production API Endpoints**

* Public endpoint: `https://livia.arryan.xyz/graphql`
* Runtime: one Oracle Cloud instance in London (internal Tailscale host `oracle`)

GraphiQL Playground: [https://livia.arryan.xyz/graphiql](https://livia.arryan.xyz/graphiql)

Status Page: [https://status.cosmofy.arryan.xyz](https://status.cosmofy.arryan.xyz)


## Overview

Cosmofy began as a static mobile astronomy app, but rapidly outgrew its architecture. This project ("Livia") introduces a centralized, cache-aware, schema-first GraphQL backend to unify data access, minimize API overfetching, and scale across device platforms.

The backend integrates with:
* **NASA APOD** (Astronomy Picture of the Day)
* **NASA EONET** (Earth Observatory Natural Event Tracker)
* **NASA JPL Horizons** (Jet Propulsion Laboratory orbital/planetary data)
* **OpenAI** (legacy `picture` content generation and summarization)
* **MongoDB** (legacy Universe and Picture persistence)
* **Cosmofy internal microservices** (APOD, News, and Articles)
* **AWS Route 53** (latency-based routing)



Backend is open-source under the [Cosmofy GitHub organization](https://github.com/Cosmofy).



## Tech Stack

| Component            | Tech/Tool                                       |
|----------------------|-------------------------------------------------|
| **Language**         | Java 21                                        |
| **Framework**        | Spring Boot + Netflix DGS (GraphQL/Federation) |
| **Runtime**          | systemd                                        |
| **CI/CD**            | GitHub Actions                                 |
| **Edge cache**       | Stellate                                       |
| **DB**               | MongoDB                                        |
| **Hosting**          | Oracle Cloud, London                           |
| **Routing**          | Public DNS to the single production instance  |


## Features

- Schema-first GraphQL API (`.graphqls`)
- Apollo Federation 2 subgraph support
- Dynamic per-device data filtering
- GPT-powered content generation and summarization
- Open-source, modular service deployment



## Modules & TTLs
| Module     | Source(s) + Processing     | TTL / Storage              |
| ---------- | -------------------------- | -------------------------- |
| `planets`  | JPL Horizons + manual      | Static (JSON)              |
| `picture`  | NASA APOD + AI summaries   | Day-end invalidation (MongoDB) |
| `events`   | NASA EONET + geo filtering | In-memory / edge cache     |
| `articles` | Cosmofy Articles microservice | Service-owned Redis + Stellate edge cache |
| `news`     | Cosmofy News microservice  | Redis/cache policy owned by News |



## Testing the API

👉 Try it with the hosted GraphiQL:
[https://livia.arryan.xyz/graphiql](https://livia.arryan.xyz/graphiql)

### APOD microservice queries

Livia exposes the internal APOD REST service through typed GraphQL operations. Clients should only call Livia. This repository is a Federation 2 subgraph, not an Apollo Router; `Apod` is an entity keyed by `date`.

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
      apod {
        date
        title
        mediaType
        url
      }
      relevanceScore
      matchTypes
    }
  }
}
```

`APOD_SERVICE_BASE_URL` defaults to the deployed non-secret APOD endpoint and can be overridden per environment. The service currently has no authentication, so Livia sends no authorization header. Connect timeout, the 35-second APOD request budget, the 15-second search budget, and retry bounds are controlled by the `APOD_*` values documented in `.env.example`.

Stellate caches `Query.apod` for at most five minutes with no stale-while-revalidate window, so a current APOD can remain stale for no more than five minutes after Mountain Time midnight. Search payloads and results are explicitly non-cacheable. The same conservative APOD TTL currently applies to historical dates.

Distributed traces use the OpenTelemetry Java agent. The `livia.service` systemd unit must start Livia with `-javaagent:/path/to/opentelemetry-javaagent.jar`, send OTLP/HTTP to a verified colocated private collector at `http://127.0.0.1:4318`, and retain the W3C `tracecontext` propagator. Do not expose the collector publicly.

### News microservice query

Live news follows `client -> Stellate -> Livia -> News microservice`. Livia does not call Spaceflight News directly and does not connect to the News Redis instance.

```graphql
query LatestNews($limit: Int!, $offset: Int!, $ordering: NewsOrdering!) {
  news(limit: $limit, offset: $offset, ordering: $ordering) {
    totalCount
    articles {
      id
      title
      summary
      url
      imageUrl
      newsSite
      publishedAt
    }
  }
}
```

The News REST API currently exposes only the paginated collection endpoint, not an exact article-by-ID endpoint. `NewsArticle` is therefore query-owned and is not declared as a Federation entity; Livia does not scan the full feed to resolve references. `Query.news`, `NewsPage`, and `NewsArticle` are explicitly non-cacheable in Stellate for this integration, leaving freshness and Redis caching to the News service.

`NEWS_SERVICE_BASE_URL` defaults to the deployed non-secret News endpoint. `NEWS_CONNECT_TIMEOUT`, `NEWS_REQUEST_TIMEOUT`, `NEWS_MAX_ATTEMPTS`, and `NEWS_RETRY_BACKOFF` control the validated HTTP policy documented in `.env.example`.

### Articles microservice queries

Curated articles follow `client -> Stellate -> Livia -> Articles microservice`. The original `articles: [Article]` query and all of its existing fields remain available to old app versions, but the data now comes from the Articles service instead of MongoDB. The service-provided UUID is exposed as `Article.id`, and `Article` is a Federation 2 entity keyed by that ID.

```graphql
query BrowseArticles {
  articlesPage(
    limit: 24
    offset: 0
    search: "dark matter"
    year: 2026
    month: 8
    source: "Quanta Magazine"
    ordering: DATE_DESCENDING
  ) {
    totalCount
    hasNextPage
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
}

query ExactArticle($id: ID!) {
  article(id: $id) {
    id
    title
    url
  }
}
```

Livia calls the exact REST route when resolving `article(id:)` or an `Article` representation through `_entities`; it never scans the collection. It does not read `articles.json`, connect to the Articles Redis instance, or duplicate the service's cache. W3C trace headers and the trusted request ID are forwarded, while cache and rate-limit response headers are recorded only as telemetry.

`ARTICLES_SERVICE_URL` defaults to `https://articles.api.cosmofy.services.deployim.com`. Production should explicitly set the same value in `/home/ubuntu/livia-oracle/.env`:

```dotenv
ARTICLES_SERVICE_URL=https://articles.api.cosmofy.services.deployim.com
```

`ARTICLES_CONNECT_TIMEOUT`, `ARTICLES_REQUEST_TIMEOUT`, `ARTICLES_MAX_ATTEMPTS`, and `ARTICLES_RETRY_BACKOFF` configure the bounded HTTP policy documented in `.env.example`.

CI combines the DGS schema files and composes them with the pinned Apollo Federation version. Run the same check locally with:

```bash
./gradlew build --no-daemon
npm ci --ignore-scripts --no-audit --no-fund
npm run compose
```

Full Schema Example Query:
```
query FullSchema {
  server
  time
  planets {
    moons
    name
    obliquityToOrbit
    orbitalInclination
    orbitalVelocity
    albedo
    angularDiameter
    atmosphere {
      formula
      molar
      name
      percentage
    }
    density
    description
    escapeVelocity
    expandedDescription
    facts
    flattening
    gravitationalParameter
    gravitationalParameterUncertainty
    gravityEquatorial
    gravityPolar
    id
    lastUpdated
    mass
    maxIR {
      aphelion
      mean
      perihelion
    }
    minIR {
      aphelion
      mean
      perihelion
    }
    pressure
    radiusEquatorial
    momentOfInertia
    radiusCore
    radiusHillsSphere
    radiusPolar
    rocheLimit
    rings
    siderealOrbitPeriodD
    rockyCoreMass
    siderealOrbitPeriodY
    siderealRotationRate
    siderealRotationPeriod
    solarConstant {
      perihelion
      mean
      aphelion
    }
    solarDayLength
    temperature
    visual
    visualMagnitude
    visualMagnitudeOpposition
    volume
    volumetricMeanRadius
  }
  picture {
    copyright
    credit
    date
    explanation {
      kids
      original
      summarized
    }
    media
    media_type
    title
  }
  events {
    categories {
      id
      title
    }
    geometry {
      coordinates
      date
      id
      magnitudeUnit
      magnitudeValue
      type
    }
    id
    sources {
      id
      url
    }
    title
  }
  articles {
    id
    authors {
      image
      name
      title
    }
    banner {
      designer
      image
    }
    month
    source
    subtitle
    title
    url
    year
  }
}
```
