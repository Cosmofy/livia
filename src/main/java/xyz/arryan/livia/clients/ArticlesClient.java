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
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.clients.dto.ArticlesErrorResponse;
import xyz.arryan.livia.config.ArticlesClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ArticlesException;
import xyz.arryan.livia.observability.TraceLogContext;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ArticlesClient {

    private static final Logger logger = LoggerFactory.getLogger(ArticlesClient.class);
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_RETRY_AFTER_SECONDS = 86_400;
    private static final long MAX_RATE_LIMIT_VALUE = Integer.MAX_VALUE;

    private final WebClient webClient;
    private final ArticlesClientProperties properties;
    private final OpenTelemetry openTelemetry;

    @Autowired
    public ArticlesClient(WebClient sharedWebClient, ArticlesClientProperties properties) {
        this(sharedWebClient, properties, GlobalOpenTelemetry.get());
    }

    public ArticlesClient(
            WebClient sharedWebClient,
            ArticlesClientProperties properties,
            OpenTelemetry openTelemetry) {
        this.properties = properties;
        this.openTelemetry = openTelemetry;

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.requestTimeout());
        String baseUrl = properties.baseUrl().toString().replaceAll("/+$", "");
        this.webClient = sharedWebClient.mutate()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    public ArticlePageResponse get(
            int limit,
            int offset,
            String search,
            Integer year,
            Integer month,
            String source,
            String ordering) {
        Context traceContext = Context.current();
        Span span = Span.fromContext(traceContext);
        span.setAttribute("articles.operation", "list");
        span.setAttribute("articles.limit", limit);
        span.setAttribute("articles.offset", offset);
        span.setAttribute("articles.filter.search_supplied", search != null);
        span.setAttribute("articles.filter.year_supplied", year != null);
        span.setAttribute("articles.filter.month_supplied", month != null);
        span.setAttribute("articles.filter.source_supplied", source != null);

        String requestId = RequestIdFilter.currentRequestId();
        Mono<ArticlePageResponse> request = webClient.get()
                .uri(uriBuilder -> buildListUri(
                        uriBuilder, limit, offset, search, year, month, source, ordering))
                .headers(headers -> addHeaders(headers, requestId, traceContext))
                .exchangeToMono(response -> decodeResponse(response, ArticlePageResponse.class, requestId, span));
        return execute(request, "list", requestId, span);
    }

    public ArticleResponse getById(UUID articleId) {
        Context traceContext = Context.current();
        Span span = Span.fromContext(traceContext);
        span.setAttribute("articles.operation", "get_by_id");
        String requestId = RequestIdFilter.currentRequestId();
        Mono<ArticleResponse> request = webClient.get()
                .uri("/articles/{articleId}", articleId)
                .headers(headers -> addHeaders(headers, requestId, traceContext))
                .exchangeToMono(response -> decodeResponse(response, ArticleResponse.class, requestId, span));
        return execute(request, "get_by_id", requestId, span);
    }

    private <T> T execute(Mono<T> source, String operation, String requestId, Span span) {
        AtomicInteger attempts = new AtomicInteger(1);
        long startedAt = System.nanoTime();
        Mono<T> request = source;
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

        try {
            T result = request
                    .timeout(properties.requestTimeout(), Mono.error(ArticlesException.timeout(null)))
                    .block();
            if (result == null) {
                throw ArticlesException.invalidResponse(null);
            }
            span.setAttribute("articles.retry.count", attempts.get() - 1L);
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.info("articles request completed operation={} attempts={} duration_ms={} status=OK",
                        operation, attempts.get(), elapsedMillis(startedAt));
            }
            return result;
        } catch (RuntimeException exception) {
            ArticlesException mapped = mapException(exception);
            span.setAttribute("articles.retry.count", attempts.get() - 1L);
            span.setAttribute("error.type", mapped.code());
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.warn("articles request failed operation={} attempts={} duration_ms={} status=ERROR code={} upstream_status={}",
                        operation, attempts.get(), elapsedMillis(startedAt), mapped.code(), mapped.httpStatus());
            }
            throw mapped;
        }
    }

    private static URI buildListUri(
            UriBuilder uriBuilder,
            int limit,
            int offset,
            String search,
            Integer year,
            Integer month,
            String source,
            String ordering) {
        uriBuilder.path("/articles")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .queryParam("ordering", ordering);
        if (search != null) {
            uriBuilder.queryParam("search", search);
        }
        if (year != null) {
            uriBuilder.queryParam("year", year);
        }
        if (month != null) {
            uriBuilder.queryParam("month", month);
        }
        if (source != null) {
            uriBuilder.queryParam("source", source);
        }
        return uriBuilder.build();
    }

    private void addHeaders(HttpHeaders headers, String requestId, Context traceContext) {
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(traceContext, headers, HttpHeaders::set);
    }

    private <T> Mono<T> decodeResponse(
            ClientResponse response,
            Class<T> responseType,
            String requestId,
            Span span) {
        HttpStatusCode status = response.statusCode();
        HttpHeaders headers = response.headers().asHttpHeaders();
        captureResponseMetadata(headers, requestId, span);
        span.setAttribute("articles.upstream.status_code", status.value());

        if (status.is2xxSuccessful()) {
            return response.bodyToMono(responseType)
                    .onErrorMap(exception -> !isTransportFailure(exception), ArticlesException::invalidResponse)
                    .switchIfEmpty(Mono.error(ArticlesException.invalidResponse(null)));
        }

        Long retryAfterSeconds = parseBoundedLong(
                headers.getFirst(HttpHeaders.RETRY_AFTER), MAX_RETRY_AFTER_SECONDS);
        return response.bodyToMono(ArticlesErrorResponse.class)
                .onErrorResume(exception -> isTransportFailure(exception)
                        ? Mono.error(exception)
                        : Mono.just(ArticlesErrorResponse.empty()))
                .defaultIfEmpty(ArticlesErrorResponse.empty())
                .flatMap(body -> Mono.error(ArticlesException.upstream(
                        body.errorCode(), fallbackCode(status.value()), status.value(), retryAfterSeconds)));
    }

    private static void captureResponseMetadata(HttpHeaders headers, String requestId, Span span) {
        String cache = boundedToken(headers.getFirst("X-Cache"));
        long limit = valueOrUnknown(parseBoundedLong(
                headers.getFirst("RateLimit-Limit"), MAX_RATE_LIMIT_VALUE));
        long remaining = valueOrUnknown(parseBoundedLong(
                headers.getFirst("RateLimit-Remaining"), MAX_RATE_LIMIT_VALUE));
        long reset = valueOrUnknown(parseBoundedLong(
                headers.getFirst("RateLimit-Reset"), Long.MAX_VALUE));
        String responseRequestId = headers.getFirst(RequestIdFilter.HEADER_NAME);
        span.setAttribute("articles.cache.status", cache);
        span.setAttribute("articles.rate_limit.limit", limit);
        span.setAttribute("articles.rate_limit.remaining", remaining);
        span.setAttribute("articles.rate_limit.reset", reset);
        span.setAttribute("articles.response.request_id_present", responseRequestId != null);
        if (responseRequestId != null) {
            span.setAttribute("articles.response.request_id_matches", responseRequestId.equals(requestId));
        }
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        return isTransportFailure(unwrapped)
                || unwrapped instanceof ArticlesException exception && exception.retryable();
    }

    private static ArticlesException mapException(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        if (unwrapped instanceof ArticlesException articlesException) {
            if ("ARTICLES_INVALID_RESPONSE".equals(articlesException.code()) && isTimeout(articlesException)) {
                return ArticlesException.timeout(articlesException.getCause());
            }
            return articlesException;
        }
        if (isTimeout(unwrapped)) {
            return ArticlesException.timeout(unwrapped);
        }
        return ArticlesException.unavailable(unwrapped);
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

    private static String fallbackCode(int status) {
        return switch (status) {
            case 404 -> "ARTICLE_NOT_FOUND";
            case 422 -> "INVALID_QUERY";
            case 429 -> "RATE_LIMITED";
            case 500 -> "INTERNAL_ERROR";
            case 503 -> "INVALID_DATASET";
            case 502 -> "ARTICLES_UNAVAILABLE";
            default -> "ARTICLES_UPSTREAM_ERROR";
        };
    }

    private static String boundedToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() <= 32 && normalized.matches("[A-Za-z0-9_.-]+")
                ? normalized
                : "other";
    }

    private static Long parseBoundedLong(String value, long maximum) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 && parsed <= maximum ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long valueOrUnknown(Long value) {
        return value == null ? -1L : value;
    }

    private static Duration maxBackoff(Duration initialBackoff) {
        Duration candidate = initialBackoff.multipliedBy(4);
        return candidate.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : candidate;
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
