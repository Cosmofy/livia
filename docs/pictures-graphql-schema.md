# Pictures GraphQL schema

This is the proposed breaking replacement for the current APOD root field.

```graphql
type Query {
  pictures: Pictures!
}

type Pictures {
  astronomy(
    date: Date
    search: String
    limit: Int = 10
  ): [Picture!]!

  earthObservatory(date: Date): EarthObservatoryPicture
}

type Picture {
  date: Date!
  title: String!
  explanation: String!
  mediaType: String!
  url: String!
  credit: String
  copyright: String
  relevanceScore: Float
  matchTypes: [PictureMatchType!]
  similar(limit: Int = 10): [SearchPicture!]
}

type SearchPicture {
  date: Date!
  title: String!
  explanation: String!
  mediaType: String!
  url: String!
  credit: String
  copyright: String
  relevanceScore: Float
  matchTypes: [PictureMatchType!]
}

enum PictureMatchType {
  LEXICAL
  SEMANTIC
}

type EarthObservatoryPicture {
  date: Date!
  title: String!
  explanation: String!
  mediaType: String!
  url: String!
  credit: String
  copyright: String
  imageDate: Date
  locationName: String
  latitude: Float
  longitude: Float
  articleUrl: String!
}
```

Examples:

```graphql
query {
  pictures {
    astronomy {
      title
      url
      mediaType
    }
  }
}
```

```graphql
query {
  pictures {
    astronomy(date: "2026-09-13") {
      date
      title
      url
    }
  }
}
```

```graphql
query {
  pictures {
    astronomy(search: "nebula", limit: 10) {
      date
      title
      url
      relevanceScore
      matchTypes
    }
  }
}
```

```graphql
query {
  pictures {
    earthObservatory(date: "2026-09-11") {
      date
      title
      url
      mediaType
      imageDate
      locationName
      latitude
      longitude
      articleUrl
    }
  }
}
```

GraphQL exposes only `url`. It does not expose `hdUrl`, `urlFallback`, or
`url_fallback`. `astronomy` accepts either `date` or `search`, never both.
`earthObservatory` currently accepts an optional date and has no search or
similarity field.
