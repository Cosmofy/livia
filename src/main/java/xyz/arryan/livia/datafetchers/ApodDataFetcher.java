package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
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
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodPicture;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.codegen.types.ApodSimilarityPayload;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.observability.TraceLogContext;
import xyz.arryan.livia.services.ApodService;

import java.time.LocalDate;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

@DgsComponent
public class ApodDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ApodDataFetcher.class);
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "date", "title", "explanation", "mediaType", "url", "hdUrl", "credit", "copyright");

    private final ApodService service;
    private final Tracer tracer;

    public ApodDataFetcher(ApodService service) {
        this.service = service;
        this.tracer = GlobalOpenTelemetry.getTracer("xyz.arryan.livia.graphql");
    }

    @DgsQuery(field = "apod")
    public Apod apod(@InputArgument LocalDate date, DgsDataFetchingEnvironment environment) {
        // The selection set includes aliases/fragments and respects skip/include directives.
        // Namespace-only requests must not retrieve an unrelated picture first.
        boolean needsLegacyPicture = environment.getSelectionSet().getImmediateFields().stream()
                .anyMatch(field -> LEGACY_FIELDS.contains(field.getName()));
        if (!needsLegacyPicture) {
            return Apod.newBuilder().build();
        }
        return traceResolver(
                "apod",
                "get",
                graphQlOperationName(environment),
                date != null,
                () -> service.get(date),
                _result -> 1L);
    }

    @DgsData(parentType = "Apod", field = "today")
    public ApodPicture today(DgsDataFetchingEnvironment environment) {
        return picture(null, "today", environment);
    }

    @DgsData(parentType = "Apod", field = "byDate")
    public ApodPicture byDate(@InputArgument LocalDate date, DgsDataFetchingEnvironment environment) {
        return picture(date, "byDate", environment);
    }

    private ApodPicture picture(LocalDate date, String field, DgsDataFetchingEnvironment environment) {
        return traceResolver(field, "get", graphQlOperationName(environment), date != null,
                () -> service.getPicture(date), _result -> 1L);
    }

    @DgsData(parentType = "Apod", field = "search")
    public ApodSearchPayload search(
            @InputArgument String query,
            @InputArgument Integer limit,
            DgsDataFetchingEnvironment environment) {
        return traceResolver(
                "search",
                "search",
                graphQlOperationName(environment),
                null,
                () -> service.search(query, limit),
                result -> result.getResults().size());
    }

    @DgsData(parentType = "Apod", field = "similar")
    public ApodSimilarityPayload similar(
            @InputArgument LocalDate date,
            @InputArgument Integer limit,
            DgsDataFetchingEnvironment environment) {
        return traceResolver("similar", "similar", graphQlOperationName(environment), true,
                () -> service.similar(date, limit), result -> result.getResults().size());
    }

    private <T> T traceResolver(
            String field,
            String apodOperation,
            String graphQlOperation,
            Boolean hasExplicitDate,
            Supplier<T> resolver,
            ToLongFunction<T> resultCount) {
        Span span = tracer.spanBuilder("graphql.resolve." + field)
                .setAttribute("graphql.operation.name", graphQlOperation)
                .setAttribute("graphql.field.name", field)
                .setAttribute("apod.operation", apodOperation)
                .startSpan();
        if (hasExplicitDate != null) {
            span.setAttribute("apod.request.has_explicit_date", hasExplicitDate);
        }
        String requestId = RequestIdFilter.currentRequestId();

        try (Scope ignored = span.makeCurrent();
             TraceLogContext ignoredLogContext = TraceLogContext.open(requestId)) {
            try {
                T result = resolver.get();
                long count = resultCount.applyAsLong(result);
                span.setAttribute("apod.result.count", count);
                span.setStatus(StatusCode.OK);
                logger.info("graphql APOD resolver completed operation={} field={} result_count={} status=OK",
                        graphQlOperation, field, count);
                return result;
            } catch (ApodException exception) {
                span.setAttribute("error.type", exception.code());
                span.setStatus(StatusCode.ERROR, exception.code());
                logger.warn("graphql APOD resolver failed operation={} field={} status=ERROR code={}",
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
