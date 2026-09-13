package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EarthObservatoryResponse(LocalDate date, String title, String explanation,
        @JsonProperty("media_type") String mediaType, String url, String credit, String copyright,
        @JsonProperty("image_date") LocalDate imageDate, @JsonProperty("location_name") String locationName,
        Double latitude, Double longitude, @JsonProperty("article_url") String articleUrl) { }
