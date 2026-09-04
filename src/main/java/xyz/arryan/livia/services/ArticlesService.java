package xyz.arryan.livia.services;

import org.springframework.stereotype.Service;
import xyz.arryan.livia.clients.ArticlesClient;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.ArticleOrdering;
import xyz.arryan.livia.codegen.types.ArticlePage;
import xyz.arryan.livia.errors.ArticlesException;
import xyz.arryan.livia.mappers.ArticlesMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ArticlesService {

    static final int DEFAULT_LIMIT = 24;
    static final int DEFAULT_OFFSET = 0;
    static final ArticleOrdering DEFAULT_ORDERING = ArticleOrdering.DATE_DESCENDING;
    private static final int LEGACY_LIMIT = 100;

    private final ArticlesClient client;
    private final ArticlesMapper mapper;

    public ArticlesService(ArticlesClient client, ArticlesMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public List<Article> legacyArticles() {
        ArticlePageResponse firstPage = client.get(
                LEGACY_LIMIT, 0, null, null, null, null, "date");
        ArticlePage mappedFirstPage = mapper.toGraphQl(firstPage);
        List<Article> articles = new ArrayList<>(mappedFirstPage.getArticles());
        for (int offset = LEGACY_LIMIT; offset < mappedFirstPage.getTotalCount(); offset += LEGACY_LIMIT) {
            ArticlePage page = mapper.toGraphQl(client.get(
                    LEGACY_LIMIT, offset, null, null, null, null, "date"));
            articles.addAll(page.getArticles());
        }
        return List.copyOf(articles);
    }

    public ArticlePage getPage(
            Integer limit,
            Integer offset,
            String search,
            Integer year,
            Integer month,
            String source,
            ArticleOrdering ordering) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        int resolvedOffset = offset == null ? DEFAULT_OFFSET : offset;
        ArticleOrdering resolvedOrdering = ordering == null ? DEFAULT_ORDERING : ordering;
        if (resolvedLimit < 1 || resolvedLimit > 100
                || resolvedOffset < 0
                || year != null && (year < 1900 || year > 2100)
                || month != null && (month < 1 || month > 12)) {
            throw ArticlesException.validation();
        }

        return mapper.toGraphQl(client.get(
                resolvedLimit,
                resolvedOffset,
                normalizeOptional(search),
                year,
                month,
                normalizeOptional(source),
                toTransportOrdering(resolvedOrdering)));
    }

    public Article getById(String id) {
        final UUID articleId;
        try {
            articleId = UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ArticlesException.validation();
        }
        return mapper.toGraphQl(client.getById(articleId));
    }

    static String toTransportOrdering(ArticleOrdering ordering) {
        return switch (ordering) {
            case DATE_ASCENDING -> "date";
            case DATE_DESCENDING -> "-date";
            case TITLE_ASCENDING -> "title";
            case TITLE_DESCENDING -> "-title";
        };
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw ArticlesException.validation();
        }
        return normalized;
    }
}
