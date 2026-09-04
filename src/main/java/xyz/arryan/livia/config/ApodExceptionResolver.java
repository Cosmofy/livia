package xyz.arryan.livia.config;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.stereotype.Component;
import xyz.arryan.livia.errors.ApodException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ApodExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final Set<Integer> ALLOWED_UPSTREAM_STATUSES = Set.of(400, 404, 422, 500, 502, 503);

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (!(exception instanceof ApodException apodException)) {
            return null;
        }

        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", apodException.code());
        extensions.put("service", "apod");
        extensions.put("requestId", RequestIdFilter.currentRequestId());
        if (apodException.httpStatus() != null
                && ALLOWED_UPSTREAM_STATUSES.contains(apodException.httpStatus())) {
            extensions.put("upstreamStatus", apodException.httpStatus());
        }

        return GraphqlErrorBuilder.newError(environment)
                .message(apodException.getMessage())
                .extensions(extensions)
                .build();
    }
}
