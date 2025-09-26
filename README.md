# Livia


A scalable, AI-augmented GraphQL backend powering the **Cosmofy** astronomy platform across iOS/iPadOS, watchOS, tvOS, macOS, visionOS, and web.

**Production API Endpoints**

* Global DNS Routing: `https://livia.arryan.xyz/graphql`
* Direct Servers:

    * 🇬🇧 London (Oracle ARM): `https://prod1.livia.arryan.xyz/graphql`
    * 🇺🇸 Dallas (Google Cloud x86): `https://prod2.livia.arryan.xyz/graphql`
    * 🇸🇬 Singapore (AWS ARM): `https://prod3.livia.arryan.xyz/graphql`

GraphiQL Playground: [https://livia.arryan.xyz/graphiql](https://livia.arryan.xyz/graphiql)


## Overview

Cosmofy began as a static mobile astronomy app, but rapidly outgrew its architecture. This project ("Livia") introduces a centralized, cache-aware, schema-first GraphQL backend to unify data access, minimize API overfetching, and scale across device platforms.

The backend integrates with:
* **NASA APOD** (Astronomy Picture of the Day)
* **NASA EONET** (Earth Observatory Natural Event Tracker)
* **NASA JPL Horizons** (Jet Propulsion Laboratory orbital/planetary data)
* **OpenAI GPT-5** (content generation, summarization, parsing)
* **MongoDB** (persistent storage)
* **Redis** (multi-region caching)
* **AWS Route 53** (latency-based routing)



Backend is deployed to Oracle Cloud and is open-source under the [Cosmofy GitHub organization](https://github.com/Cosmofy).



## Tech Stack

| Component            | Tech/Tool                                       |
|----------------------|-------------------------------------------------|
| **Language**         | Java 24                                         |
| **Framework**        | Spring Boot + Netflix DGS (GraphQL)             |
| **Containerization** | Docker                                          |
| **CI/CD**            | GitHub Actions                                  |
| **Caching**          | Redis                                           |
| **DB**               | MongoDB                                         |
| **AI Models**        | OpenAI GPT-5 and 5-Mini for parsing & summaries |
| **Hosting**          | Multi-cloud (Oracle, GCP, AWS)                  |
| **Routing**          | AWS Route 53 latency-based DNS                  |


## Features

- Schema-first GraphQL API (`.graphqls`)
- Dynamic per-device data filtering
- Redis/Valkey TTL-based caching
- GPT-powered content generation and parsing
- Cron jobs for APOD preloading
- Open-source, modular, and containerized



## Modules & TTLs
| Module     | Source(s) + Processing     | TTL / Storage                         |
| ---------- | -------------------------- | ------------------------------------- |
| `planets`  | JPL Horizons + GPT parsing | 30 days (Redis)                       |
| `picture`  | NASA APOD + AI summaries   | Day-end invalidation (MongoDB)        |
| `events`   | NASA EONET + geo filtering | 1 hour (Redis)                        |
| `articles` | Curated monthly content    | Month-end invalidation (JSON/MongoDB) |



## Testing the API

👉 Try it with the hosted GraphiQL:
[https://livia.arryan.xyz/graphiql](https://livia.arryan.xyz/graphiql)

Full Schema Example Query:
```
query FullSchema {
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
