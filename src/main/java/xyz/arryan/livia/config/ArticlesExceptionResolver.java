package xyz.arryan.livia.config;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;
import xyz.arryan.livia.errors.ArticlesException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ArticlesExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Set<Integer> ALLOWED_UPSTREAM_STATUSES = Set.of(404, 422, 429, 500, 502, 503);

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof ArticlesException articlesException)) {
            return null;
        }

        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", articlesException.code());
        extensions.put("service", "articles");
        extensions.put("requestId", RequestIdFilter.currentRequestId());
        if (articlesException.httpStatus() != null
                && ALLOWED_UPSTREAM_STATUSES.contains(articlesException.httpStatus())) {
            extensions.put("upstreamStatus", articlesException.httpStatus());
        }
        if (articlesException.retryAfterSeconds() != null) {
            extensions.put("retryAfterSeconds", articlesException.retryAfterSeconds());
        }

        return GraphqlErrorBuilder.newError(environment)
                .message(articlesException.getMessage())
                .extensions(extensions)
                .build();
    }
}
