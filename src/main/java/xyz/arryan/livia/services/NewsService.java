package xyz.arryan.livia.services;

import org.springframework.stereotype.Service;
import xyz.arryan.livia.clients.NewsClient;
import xyz.arryan.livia.codegen.types.NewsOrdering;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.errors.NewsException;
import xyz.arryan.livia.mappers.NewsMapper;

@Service
public class NewsService {

    static final int DEFAULT_LIMIT = 24;
    static final int DEFAULT_OFFSET = 0;
    static final NewsOrdering DEFAULT_ORDERING = NewsOrdering.PUBLISHED_AT_DESCENDING;

    private final NewsClient client;
    private final NewsMapper mapper;

    public NewsService(NewsClient client, NewsMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public NewsPage get(
            Integer limit,
            Integer offset,
            String search,
            NewsOrdering ordering,
            String newsSite) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        int resolvedOffset = offset == null ? DEFAULT_OFFSET : offset;
        NewsOrdering resolvedOrdering = ordering == null ? DEFAULT_ORDERING : ordering;
        if (resolvedLimit < 1 || resolvedLimit > 500 || resolvedOffset < 0) {
            throw NewsException.validation();
        }

        String normalizedSearch = normalizeOptional(search);
        String normalizedNewsSite = normalizeOptional(newsSite);
        return mapper.toGraphQl(client.get(
                resolvedLimit,
                resolvedOffset,
                normalizedSearch,
                toTransportOrdering(resolvedOrdering),
                normalizedNewsSite));
    }

    static String toTransportOrdering(NewsOrdering ordering) {
        return switch (ordering) {
            case PUBLISHED_AT_ASCENDING -> "published_at";
            case PUBLISHED_AT_DESCENDING -> "-published_at";
            case UPDATED_AT_ASCENDING -> "updated_at";
            case UPDATED_AT_DESCENDING -> "-updated_at";
        };
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw NewsException.validation();
        }
        return normalized;
    }
}
