package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.NewsArticleResponse;
import xyz.arryan.livia.clients.dto.NewsPageResponse;
import xyz.arryan.livia.codegen.types.NewsArticle;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.errors.NewsException;

import java.util.List;

@Component
public class NewsMapper {

    public NewsPage toGraphQl(NewsPageResponse response) {
        if (response == null
                || response.totalCount() == null || response.totalCount() < 0
                || response.limit() == null || response.limit() < 1 || response.limit() > 500
                || response.offset() == null || response.offset() < 0
                || response.hasNextPage() == null
                || response.hasPreviousPage() == null
                || response.articles() == null) {
            throw NewsException.invalidResponse(null);
        }

        List<NewsArticle> articles = response.articles().stream()
                .map(this::toGraphQl)
                .toList();
        return NewsPage.newBuilder()
                .totalCount(response.totalCount())
                .limit(response.limit())
                .offset(response.offset())
                .hasNextPage(response.hasNextPage())
                .hasPreviousPage(response.hasPreviousPage())
                .articles(articles)
                .build();
    }

    private NewsArticle toGraphQl(NewsArticleResponse response) {
        if (response == null
                || response.id() == null
                || response.title() == null
                || response.summary() == null
                || isBlank(response.url())
                || isBlank(response.imageUrl())
                || response.newsSite() == null
                || containsNull(response.authors())
                || response.publishedAt() == null
                || response.updatedAt() == null
                || response.featured() == null
                || containsNull(response.launchIds())
                || containsNull(response.eventIds())) {
            throw NewsException.invalidResponse(null);
        }

        return NewsArticle.newBuilder()
                .id(String.valueOf(response.id()))
                .title(response.title())
                .summary(response.summary())
                .url(response.url())
                .imageUrl(response.imageUrl())
                .newsSite(response.newsSite())
                .authors(response.authors())
                .publishedAt(response.publishedAt())
                .updatedAt(response.updatedAt())
                .featured(response.featured())
                .launchIds(response.launchIds())
                .eventIds(response.eventIds().stream().map(String::valueOf).toList())
                .build();
    }

    private static boolean containsNull(List<?> values) {
        return values == null || values.stream().anyMatch(value -> value == null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
