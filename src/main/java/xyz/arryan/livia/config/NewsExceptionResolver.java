package xyz.arryan.livia.config;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;
import xyz.arryan.livia.errors.NewsException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class NewsExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Set<Integer> ALLOWED_UPSTREAM_STATUSES = Set.of(404, 422, 429, 500, 502, 503);

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof NewsException newsException)) {
            return null;
        }

        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", newsException.code());
        extensions.put("service", "news");
        extensions.put("requestId", RequestIdFilter.currentRequestId());
        if (newsException.httpStatus() != null
                && ALLOWED_UPSTREAM_STATUSES.contains(newsException.httpStatus())) {
            extensions.put("upstreamStatus", newsException.httpStatus());
        }
        if (newsException.retryAfterSeconds() != null) {
            extensions.put("retryAfterSeconds", newsException.retryAfterSeconds());
        }

        return GraphqlErrorBuilder.newError(environment)
                .message(newsException.getMessage())
                .extensions(extensions)
                .build();
    }
}
