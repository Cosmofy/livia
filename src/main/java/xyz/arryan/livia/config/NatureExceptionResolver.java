package xyz.arryan.livia.config;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;
import xyz.arryan.livia.errors.NatureException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class NatureExceptionResolver extends DataFetcherExceptionResolverAdapter {
    private static final Set<Integer> UPSTREAM_STATUSES = Set.of(422, 502, 503);

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof NatureException natureException)) return null;
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", natureException.code());
        extensions.put("service", "nature");
        extensions.put("requestId", RequestIdFilter.currentRequestId());
        if (natureException.httpStatus() != null && UPSTREAM_STATUSES.contains(natureException.httpStatus())) {
            extensions.put("upstreamStatus", natureException.httpStatus());
        }
        if (natureException.retryAfterSeconds() != null) {
            extensions.put("retryAfterSeconds", natureException.retryAfterSeconds());
        }
        return GraphqlErrorBuilder.newError(environment).message(natureException.getMessage()).extensions(extensions).build();
    }
}
