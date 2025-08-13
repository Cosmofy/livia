# Livia


A scalable, AI-augmented GraphQL backend powering the **Cosmofy** astronomy platform across iOS/iPadOS, watchOS, tvOS, macOS, visionOS, and web.

API Endpoint: https://livia.arryan.xyz/graphql

## Overview

Cosmofy began as a static mobile astronomy app, but rapidly outgrew its architecture. This project ("Livia") introduces a centralized, cache-aware, schema-first GraphQL backend to unify data access, minimize API overfetching, and scale across device platforms.

The backend integrates with:
- **NASA APOD**
- **NASA EONET**
- **JPL Horizons**
- **OpenAI**
- **MongoDB**
- **Redis/Valkey**
- **Cloudflare CDN**


Backend is deployed to Oracle Cloud and is open-source under the [Cosmofy GitHub organization](https://github.com/Cosmofy).



## Tech Stack

| Component         | Tech/Tool                            |
|------------------|--------------------------------------|
| **Language**      | Java 24                              |
| **Framework**     | Spring Boot + Netflix DGS (GraphQL)  |
| **Containerization** | Docker                               |
| **CI/CD**         | GitHub Actions                       |
| **Caching**       | Redis (Valkey compatible)            |
| **DB**            | MongoDB                              |
| **AI/NLP**        | OpenAI (GPT-5) for summary + parsing |
| **Hosting**       | Oracle Cloud ARM (Always Free)       |
| **Edge/CDN**      | Cloudflare                           |


## Features

- Schema-first GraphQL API (`.graphqls`)
- Dynamic per-device data filtering
- Redis/Valkey TTL-based caching
- GPT-powered content generation and parsing
- Cron jobs for APOD preloading
- Open-source, modular, and containerized



## Modules & TTLs

| Module        | Description                          | TTL          |
|---------------|--------------------------------------|--------------|
| `planets`     | Planetary data from JPL + OpenAI     | 30 days      |
| `picture`     | APOD + AI summaries                  | 24 hours     |
| `events`      | Natural disasters from EONET         | 4 hours      |
| `articles`    | Monthly curated articles             | MongoDB only |



## Testing the API

Use the hosted GraphiQL instance:

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