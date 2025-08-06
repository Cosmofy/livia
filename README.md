# 🌌 Cosmofy Backend – Livia

A scalable, AI-augmented GraphQL backend powering the **Cosmofy** astronomy platform across iOS, iPadOS, watchOS, tvOS, and web.

---

## 🔭 Overview

Cosmofy began as a static mobile astronomy app, but rapidly outgrew its architecture. This project ("Livia") introduces a centralized, cache-aware, schema-first GraphQL backend to unify data access, minimize API overfetching, and scale across device platforms.

The backend integrates with:
- **NASA APOD**
- **NASA EONET**
- **JPL Horizons**
- **OpenAI**
- **MongoDB**
- **Redis (or Valkey)**

Backend is deployed to Oracle Cloud and is open-source under the [Cosmofy GitHub organization](https://github.com/Cosmofy).

---

## ⚙️ Tech Stack

| Component         | Tech/Tool                                   |
|------------------|---------------------------------------------|
| **Language**      | Java 21                                     |
| **Framework**     | Spring Boot + Netflix DGS (GraphQL)         |
| **Containerization** | Docker                                  |
| **CI/CD**         | GitHub Actions                              |
| **Caching**       | Redis (Valkey compatible)                   |
| **DB**            | MongoDB                                     |
| **AI/NLP**        | OpenAI (GPT-4) for summary + parsing        |
| **Hosting**       | Oracle Cloud ARM (Always Free)              |
| **Edge/CDN**      | Cloudflare                                  |

---

## 🚀 Features

- ✅ Schema-first GraphQL API (`.graphqls`)
- ✅ Dynamic per-device data filtering
- ✅ Redis/Valkey TTL-based caching
- ✅ GPT-powered content generation and parsing
- ✅ Cron jobs for APOD preloading
- ✅ Open-source, modular, and containerized

---

## 📦 Modules & TTLs

| Module        | Description                          | TTL        |
|---------------|--------------------------------------|------------|
| `planets`     | Planetary data from JPL + OpenAI     | 30 days    |
| `picture`     | APOD + AI summaries                  | 24 hours   |
| `events`      | Natural disasters from EONET         | 1 hour     |
| `articles`    | Monthly curated articles             | MongoDB only |

---

## 🧪 Testing the API

Use the hosted GraphiQL instance:

👉 [https://api.arryan.xyz/graphiql](https://api.arryan.xyz/graphiql)

Example query:
```graphql
{
  planets {
    name
    temperature
    gravityEquatorial\\
  }
}