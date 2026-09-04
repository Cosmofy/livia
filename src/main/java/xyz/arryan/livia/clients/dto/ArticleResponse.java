package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ArticleResponse(
        UUID id,
        Integer month,
        Integer year,
        String title,
        String subtitle,
        String url,
        String source,
        ArticleBannerResponse banner,
        List<ArticleAuthorResponse> authors) {
}
