package xyz.arryan.livia.clients;

import io.netty.channel.ChannelOption;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import xyz.arryan.livia.clients.dto.ApodErrorResponse;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResponse;
import xyz.arryan.livia.config.ApodClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.observability.TraceLogContext;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Component
public class ApodClient {

    private static final Logger logger = LoggerFactory.getLogger(ApodClient.class);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final WebClient webClient;
    private final ApodClientProperties properties;
    private final OpenTelemetry openTelemetry;

    @Autowired
    public ApodClient(WebClient sharedWebClient, ApodClientProperties properties) {
        this(sharedWebClient, properties, GlobalOpenTelemetry.get());
    }

    public ApodClient(WebClient sharedWebClient, ApodClientProperties properties, OpenTelemetry openTelemetry) {
        this.properties = properties;
        this.openTelemetry = openTelemetry;

        Duration responseTimeout = properties.apodRequestTimeout().compareTo(properties.searchRequestTimeout()) >= 0
                ? properties.apodRequestTimeout()
                : properties.searchRequestTimeout();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(responseTimeout);

        String baseUrl = properties.baseUrl().toString().replaceAll("/+$", "");
        this.webClient = sharedWebClient.mutate()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    public ApodResponse get(LocalDate date) {
        return execute(
                "get",
                date != null,
                properties.apodRequestTimeout(),
                uriBuilder -> {
                    uriBuilder.path("/apod");
                    if (date != null) {
                        uriBuilder.queryParam("date", date);
                    }
                    return uriBuilder.build();
                },
                ApodResponse.class);
    }

    public ApodSearchResponse search(String query, int limit) {
        return execute(
                "search",
                false,
                properties.searchRequestTimeout(),
                uriBuilder -> uriBuilder.path("/vector/search")
                        .queryParam("q", query)
                        .queryParam("limit", limit)
                        .build(),
                ApodSearchResponse.class);
    }

    public ApodSimilarityResponse similar(LocalDate date, int limit) {
        return execute("similar", true, properties.searchRequestTimeout(),
                uriBuilder -> uriBuilder.path("/vector/similar")
                        .queryParam("date", date)
                        .queryParam("limit", limit)
                        .build(),
                ApodSimilarityResponse.class);
    }

    private <T> T execute(
            String operation,
            boolean hasExplicitDate,
            Duration requestTimeout,
            Function<UriBuilder, URI> uri,
            Class<T> responseType) {
        Context traceContext = Context.current();
        Span callerSpan = Span.fromContext(traceContext);
        callerSpan.setAttribute("apod.operation", operation);
        if ("get".equals(operation)) {
            callerSpan.setAttribute("apod.request.has_explicit_date", hasExplicitDate);
        }

        String requestId = RequestIdFilter.currentRequestId();
        AtomicInteger attempts = new AtomicInteger(1);
        long startedAt = System.nanoTime();

        try {
            Mono<T> request = webClient.get()
                    .uri(uri)
                    .headers(headers -> addHeaders(headers, requestId, traceContext))
                    .exchangeToMono(response -> decodeResponse(
                            operation, response.statusCode(), response, responseType, callerSpan));

            if (properties.maxAttempts() > 1) {
                request = request.retryWhen(Retry.backoff(
                                properties.maxAttempts() - 1L,
                                properties.retryBackoff())
                        .maxBackoff(maxBackoff(properties.retryBackoff()))
                        .jitter(0.25)
                        .filter(this::isRetryable)
                        .doBeforeRetry(_signal -> attempts.incrementAndGet())
                        .onRetryExhaustedThrow((_spec, signal) -> signal.failure()));
            }

            T result = request
                    .timeout(requestTimeout, Mono.error(ApodException.timeout(null)))
                    .block();
            if (result == null) {
                throw ApodException.invalidResponse(null);
            }

            callerSpan.setAttribute("apod.retry.count", attempts.get() - 1L);
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.info("apod request completed operation={} attempts={} duration_ms={} status=OK",
                        operation, attempts.get(), elapsedMillis(startedAt));
            }
            return result;
        } catch (RuntimeException exception) {
            ApodException mapped = mapException(exception);
            callerSpan.setAttribute("apod.retry.count", attempts.get() - 1L);
            callerSpan.setAttribute("error.type", mapped.code());
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.warn("apod request failed operation={} attempts={} duration_ms={} status=ERROR code={} upstream_status={}",
                        operation, attempts.get(), elapsedMillis(startedAt), mapped.code(), mapped.httpStatus());
            }
            throw mapped;
        }
    }

    private void addHeaders(HttpHeaders headers, String requestId, Context traceContext) {
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(traceContext, headers, HttpHeaders::set);
    }

    private <T> Mono<T> decodeResponse(
            String operation,
            HttpStatusCode status,
            ClientResponse response,
            Class<T> responseType,
            Span callerSpan) {
        callerSpan.setAttribute("apod.upstream.status_code", status.value());
        if (status.is2xxSuccessful()) {
            return response.bodyToMono(responseType)
                    .onErrorMap(exception -> !isTransportFailure(exception), ApodException::invalidResponse)
                    .switchIfEmpty(Mono.error(ApodException.invalidResponse(null)));
        }

        return response.bodyToMono(ApodErrorResponse.class)
                .onErrorResume(exception -> isTransportFailure(exception)
                        ? Mono.error(exception)
                        : Mono.just(ApodErrorResponse.empty()))
                .defaultIfEmpty(ApodErrorResponse.empty())
                .flatMap(body -> Mono.error(ApodException.upstream(
                        body.errorCode(), fallbackCode(operation, status.value()), status.value())));
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        return isTransportFailure(unwrapped)
                || unwrapped instanceof ApodException exception && exception.retryable();
    }

    private static ApodException mapException(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        if (unwrapped instanceof ApodException apodException) {
            if ("APOD_INVALID_RESPONSE".equals(apodException.code()) && isTimeout(apodException)) {
                return ApodException.timeout(apodException.getCause());
            }
            return apodException;
        }
        if (isTimeout(unwrapped)) {
            return ApodException.timeout(unwrapped);
        }
        return ApodException.unavailable(unwrapped);
    }

    private static boolean isTransportFailure(Throwable throwable) {
        Throwable current = Exceptions.unwrap(throwable);
        while (current != null) {
            String simpleName = current.getClass().getSimpleName();
            Package exceptionPackage = current.getClass().getPackage();
            String packageName = exceptionPackage == null ? "" : exceptionPackage.getName();
            if (current instanceof WebClientRequestException
                    || current instanceof TimeoutException
                    || simpleName.contains("Timeout")
                    || simpleName.contains("PrematureClose")
                    || simpleName.contains("Aborted")
                    || packageName.startsWith("io.netty.handler.timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String fallbackCode(String operation, int status) {
        if (status == 422) {
            if ("similar".equals(operation)) return "INVALID_SIMILARITY_REQUEST";
            return "search".equals(operation) ? "INVALID_SEARCH_QUERY" : "INVALID_DATE_FORMAT";
        }
        // An undeployed route returns an unstructured 404, not a missing APOD.
        if (status == 404 && "similar".equals(operation)) return "SIMILARITY_UNAVAILABLE";
        return switch (status) {
            case 404 -> "NOT_FOUND";
            case 500 -> "INTERNAL_ERROR";
            case 502, 503 -> "APOD_UNAVAILABLE";
            default -> "APOD_UPSTREAM_ERROR";
        };
    }

    private static Duration maxBackoff(Duration initialBackoff) {
        Duration candidate = initialBackoff.multipliedBy(4);
        return candidate.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : candidate;
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
