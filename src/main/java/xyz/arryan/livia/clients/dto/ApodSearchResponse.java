package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApodSearchResponse(
        String query,
        @JsonProperty("search_mode") String searchMode,
        List<ApodSearchResultResponse> results) {
}
