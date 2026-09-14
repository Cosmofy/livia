package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record EarthObservatorySearchResponse(
        String query,
        @JsonProperty("search_mode") String searchMode,
        List<EarthObservatorySearchResultResponse> results) {}
