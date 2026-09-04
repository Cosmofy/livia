package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticlePageResponse(
        @JsonProperty("total_count") Integer totalCount,
        Integer limit,
        Integer offset,
        @JsonProperty("has_next_page") Boolean hasNextPage,
        @JsonProperty("has_previous_page") Boolean hasPreviousPage,
        List<ArticleResponse> articles) {
}
