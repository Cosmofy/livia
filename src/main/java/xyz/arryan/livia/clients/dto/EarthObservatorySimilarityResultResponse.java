package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

public record EarthObservatorySimilarityResultResponse(
        LocalDate date, String title, String explanation,
        @JsonProperty("media_type") String mediaType, String url, String credit, String copyright,
        @JsonProperty("image_date") LocalDate imageDate, @JsonProperty("location_name") String locationName,
        Double latitude, Double longitude, @JsonProperty("article_url") String articleUrl,
        @JsonProperty("relevance_score") Double relevanceScore) {}
