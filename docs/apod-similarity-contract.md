# APOD similarity REST contract

Livia calls `GET /vector/similar?date=YYYY-MM-DD&limit=10` on the configured
APOD service. The APOD service owns the similarity implementation and deployment.

- `date` is required and follows existing APOD archive/date validation.
- `limit` defaults to 10 and must be between 1 and 50.
- Use the source APOD's stored vector. Exclude the source date and duplicate dates.
- Return at most `limit` matches, ordered by descending relevance.
- Score is `max(0, min(1, 1 - cosine_distance))`; values must be finite.
- Preserve the existing lookup and text-search contracts.
- Continue `x-request-id`, `traceparent`, and `tracestate` correlation.

Successful response (an empty `results` list is valid):

```json
{
  "date": "2024-02-29",
  "results": [
    {
      "date": "2024-01-01",
      "title": "A related picture",
      "explanation": "Its explanation",
      "media_type": "image",
      "url": "https://example.com/picture.jpg",
      "hdurl": null,
      "fallback_url": null,
      "credit": null,
      "copyright": null,
      "relevance_score": 0.8
    }
  ]
}
```

All result fields follow `SourceApod`, with the additional required score.
Nullable `fallback_url` maps to GraphQL `fallbackUrl`. Preserve the service-owned
media URLs, including S3 URLs for verified archived assets and the original
source fallback. Omitted fallback values from older service responses map to null.
There is no nested picture object and no required `match_types` field.

Errors use the existing `{"error":{"code":"...","message":"..."}}` envelope:

| Situation | HTTP status | Code |
| --- | --- | --- |
| Invalid date format | 422 | `INVALID_DATE_FORMAT` |
| Before archive start | 400 | `DATE_TOO_EARLY` |
| Future date | 400 | `DATE_IN_FUTURE` |
| Source APOD missing | 404 | `NOT_FOUND` |
| Invalid limit | 422 | `INVALID_SIMILARITY_REQUEST` |
| Source vector missing or similarity infrastructure unavailable | 503 | `SIMILARITY_UNAVAILABLE` |

Test validation, default/bounded limits, ordering, source exclusion, duplicate
exclusion, complete fields, empty results, error responses, and trace propagation.

Livia uses its bounded search timeout/retry policy. It verifies the source date,
result count, scores, ordering, uniqueness, and source exclusion. An undeployed
route's unstructured 404 maps to `SIMILARITY_UNAVAILABLE`; a structured
`NOT_FOUND` remains a missing-picture error. The GraphQL `similar` field is
nullable, and both search and similarity are excluded from Stellate caching.
