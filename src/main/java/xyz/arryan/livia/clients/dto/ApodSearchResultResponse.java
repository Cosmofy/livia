package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApodSearchResultResponse(
        LocalDate date,
        String title,
        String explanation,
        @JsonProperty("media_type") String mediaType,
        String url,
        String hdurl,
        String credit,
        String copyright,
        @JsonProperty("relevance_score") Double relevanceScore,
        @JsonProperty("match_types") List<String> matchTypes,
        @JsonProperty("fallback_url") String fallbackUrl) {
}
