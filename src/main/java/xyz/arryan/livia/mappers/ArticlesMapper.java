package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.ArticleAuthorResponse;
import xyz.arryan.livia.clients.dto.ArticleBannerResponse;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.Author;
import xyz.arryan.livia.codegen.types.Banner;
import xyz.arryan.livia.errors.ArticlesException;

import java.util.List;

@Component
public class ArticlesMapper {

    public List<Article> toGraphQlArticles(ArticlePageResponse response) {
        if (response == null
                || response.totalCount() == null || response.totalCount() < 0
                || response.limit() == null || response.limit() < 1 || response.limit() > 100
                || response.offset() == null || response.offset() < 0
                || response.hasNextPage() == null
                || response.hasPreviousPage() == null
                || response.articles() == null) {
            throw ArticlesException.invalidResponse(null);
        }

        return response.articles().stream()
                .map(this::toGraphQl)
                .toList();
    }

    public Article toGraphQl(ArticleResponse response) {
        if (response == null
                || response.id() == null
                || response.month() == null || response.month() < 1 || response.month() > 12
                || response.year() == null || response.year() < 1900 || response.year() > 2100
                || response.title() == null
                || response.subtitle() == null
                || isBlank(response.url())
                || response.source() == null
                || response.banner() == null
                || response.authors() == null
                || response.authors().isEmpty()
                || response.authors().stream().anyMatch(author -> author == null)) {
            throw ArticlesException.invalidResponse(null);
        }

        return Article.newBuilder()
                .id(response.id().toString())
                .month(response.month())
                .year(response.year())
                .title(response.title())
                .subtitle(response.subtitle())
                .url(response.url())
                .source(response.source())
                .banner(toGraphQl(response.banner()))
                .authors(response.authors().stream().map(this::toGraphQl).toList())
                .build();
    }

    private Banner toGraphQl(ArticleBannerResponse response) {
        if (isBlank(response.image()) || response.designer() == null) {
            throw ArticlesException.invalidResponse(null);
        }
        return Banner.newBuilder()
                .image(response.image())
                .designer(response.designer())
                .build();
    }

    private Author toGraphQl(ArticleAuthorResponse response) {
        if (response.name() == null || response.title() == null || isBlank(response.image())) {
            throw ArticlesException.invalidResponse(null);
        }
        return Author.newBuilder()
                .name(response.name())
                .title(response.title())
                .image(response.image())
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
