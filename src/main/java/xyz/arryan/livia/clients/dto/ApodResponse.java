package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApodResponse(
        LocalDate date,
        String title,
        String explanation,
        @JsonProperty("media_type") String mediaType,
        String url,
        String hdurl,
        String credit,
        String copyright,
        @JsonProperty("fallback_url") String fallbackUrl) {
}
