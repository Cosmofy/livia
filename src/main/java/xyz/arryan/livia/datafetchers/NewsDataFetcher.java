package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.arryan.livia.codegen.types.NewsOrdering;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.NewsException;
import xyz.arryan.livia.observability.TraceLogContext;
import xyz.arryan.livia.services.NewsService;

@DgsComponent
public class NewsDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(NewsDataFetcher.class);

    private final NewsService service;
    private final Tracer tracer;

    public NewsDataFetcher(NewsService service) {
        this.service = service;
        this.tracer = GlobalOpenTelemetry.getTracer("xyz.arryan.livia.graphql");
    }

    @DgsQuery(field = "news")
    public NewsPage news(
            @InputArgument Integer limit,
            @InputArgument Integer offset,
            @InputArgument String search,
            @InputArgument NewsOrdering ordering,
            @InputArgument String newsSite,
            DgsDataFetchingEnvironment environment) {
        int resolvedLimit = limit == null ? 24 : limit;
        int resolvedOffset = offset == null ? 0 : offset;
        String operation = graphQlOperationName(environment);
        Span span = tracer.spanBuilder("graphql.resolve.news")
                .setAttribute("graphql.operation.name", operation)
                .setAttribute("graphql.field.name", "news")
                .setAttribute("news.operation", "get")
                .setAttribute("news.limit", resolvedLimit)
                .setAttribute("news.offset", resolvedOffset)
                .setAttribute("news.filter.search_supplied", search != null)
                .setAttribute("news.filter.site_supplied", newsSite != null)
                .startSpan();
        String requestId = RequestIdFilter.currentRequestId();

        try (Scope ignored = span.makeCurrent();
             TraceLogContext ignoredLogContext = TraceLogContext.open(requestId)) {
            try {
                NewsPage result = service.get(limit, offset, search, ordering, newsSite);
                int resultCount = result.getArticles().size();
                span.setAttribute("news.result.count", resultCount);
                span.setStatus(StatusCode.OK);
                logger.info("graphql News resolver completed operation={} field=news limit={} offset={} search_supplied={} site_supplied={} result_count={} status=OK",
                        operation, resolvedLimit, resolvedOffset, search != null, newsSite != null, resultCount);
                return result;
            } catch (NewsException exception) {
                span.setAttribute("error.type", exception.code());
                span.setStatus(StatusCode.ERROR, exception.code());
                logger.warn("graphql News resolver failed operation={} field=news limit={} offset={} search_supplied={} site_supplied={} status=ERROR code={}",
                        operation, resolvedLimit, resolvedOffset, search != null, newsSite != null, exception.code());
                throw exception;
            }
        } finally {
            span.end();
        }
    }

    private static String graphQlOperationName(DgsDataFetchingEnvironment environment) {
        if (environment == null || environment.getOperationDefinition() == null) {
            return "anonymous";
        }
        String operation = environment.getOperationDefinition().getName();
        if (operation == null || operation.isBlank()) {
            return "anonymous";
        }
        return operation.length() <= 128 ? operation : operation.substring(0, 128);
    }
}
