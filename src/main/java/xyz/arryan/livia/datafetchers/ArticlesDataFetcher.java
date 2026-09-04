package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsEntityFetcher;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.ArticleOrdering;
import xyz.arryan.livia.codegen.types.ArticlePage;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ArticlesException;
import xyz.arryan.livia.observability.TraceLogContext;
import xyz.arryan.livia.services.ArticlesService;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

@DgsComponent
public class ArticlesDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ArticlesDataFetcher.class);

    private final ArticlesService service;
    private final Tracer tracer;

    public ArticlesDataFetcher(ArticlesService service) {
        this.service = service;
        this.tracer = GlobalOpenTelemetry.getTracer("xyz.arryan.livia.graphql");
    }

    @DgsQuery(field = "articles")
    public List<Article> articles(DgsDataFetchingEnvironment environment) {
        return traceResolver(
                "articles",
                "legacy_list",
                graphQlOperationName(environment),
                service::legacyArticles,
                List::size);
    }

    @DgsQuery(field = "articlesPage")
    public ArticlePage articlesPage(
            @InputArgument Integer limit,
            @InputArgument Integer offset,
            @InputArgument String search,
            @InputArgument Integer year,
            @InputArgument Integer month,
            @InputArgument String source,
            @InputArgument ArticleOrdering ordering,
            DgsDataFetchingEnvironment environment) {
        return traceResolver(
                "articlesPage",
                "list",
                graphQlOperationName(environment),
                () -> service.getPage(limit, offset, search, year, month, source, ordering),
                result -> result.getArticles().size());
    }

    @DgsQuery(field = "article")
    public Article article(
            @InputArgument String id,
            DgsDataFetchingEnvironment environment) {
        return traceResolver(
                "article",
                "get_by_id",
                graphQlOperationName(environment),
                () -> service.getById(id),
                _result -> 1L);
    }

    @DgsEntityFetcher(name = "Article")
    public Article articleEntity(Map<String, Object> representation) {
        Object id = representation.get("id");
        return traceResolver(
                "_entities",
                "entity",
                "federation_entity",
                () -> service.getById(id == null ? null : String.valueOf(id)),
                _result -> 1L);
    }

    private <T> T traceResolver(
            String field,
            String articlesOperation,
            String graphQlOperation,
            Supplier<T> resolver,
            ToLongFunction<T> resultCount) {
        Span span = tracer.spanBuilder("graphql.resolve." + field)
                .setAttribute("graphql.operation.name", graphQlOperation)
                .setAttribute("graphql.field.name", field)
                .setAttribute("articles.operation", articlesOperation)
                .startSpan();
        String requestId = RequestIdFilter.currentRequestId();

        try (Scope ignored = span.makeCurrent();
             TraceLogContext ignoredLogContext = TraceLogContext.open(requestId)) {
            try {
                T result = resolver.get();
                long count = resultCount.applyAsLong(result);
                span.setAttribute("articles.result.count", count);
                span.setStatus(StatusCode.OK);
                logger.info("graphql Articles resolver completed operation={} field={} result_count={} status=OK",
                        graphQlOperation, field, count);
                return result;
            } catch (ArticlesException exception) {
                span.setAttribute("error.type", exception.code());
                span.setStatus(StatusCode.ERROR, exception.code());
                logger.warn("graphql Articles resolver failed operation={} field={} status=ERROR code={}",
                        graphQlOperation, field, exception.code());
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
