package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NewsArticleResponse(
        Long id,
        String title,
        String summary,
        String url,
        @JsonProperty("image_url") String imageUrl,
        @JsonProperty("news_site") String newsSite,
        List<String> authors,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        Boolean featured,
        @JsonProperty("launch_ids") List<String> launchIds,
        @JsonProperty("event_ids") List<Long> eventIds) {
}
