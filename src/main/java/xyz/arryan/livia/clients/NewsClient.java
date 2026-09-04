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
import xyz.arryan.livia.clients.dto.NewsErrorResponse;
import xyz.arryan.livia.clients.dto.NewsPageResponse;
import xyz.arryan.livia.config.NewsClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.NewsException;
import xyz.arryan.livia.observability.TraceLogContext;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NewsClient {

    private static final Logger logger = LoggerFactory.getLogger(NewsClient.class);
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_RETRY_AFTER_SECONDS = 86_400;

    private final WebClient webClient;
    private final NewsClientProperties properties;
    private final OpenTelemetry openTelemetry;

    @Autowired
    public NewsClient(WebClient sharedWebClient, NewsClientProperties properties) {
        this(sharedWebClient, properties, GlobalOpenTelemetry.get());
    }

    public NewsClient(WebClient sharedWebClient, NewsClientProperties properties, OpenTelemetry openTelemetry) {
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

    public NewsPageResponse get(
            int limit,
            int offset,
            String search,
            String ordering,
            String newsSite) {
        Context traceContext = Context.current();
        Span callerSpan = Span.fromContext(traceContext);
        callerSpan.setAttribute("news.operation", "get");
        callerSpan.setAttribute("news.limit", limit);
        callerSpan.setAttribute("news.offset", offset);
        callerSpan.setAttribute("news.filter.search_supplied", search != null);
        callerSpan.setAttribute("news.filter.site_supplied", newsSite != null);

        String requestId = RequestIdFilter.currentRequestId();
        AtomicInteger attempts = new AtomicInteger(1);
        AtomicReference<String> cacheStatus = new AtomicReference<>("unknown");
        AtomicLong rateLimitRemaining = new AtomicLong(-1);
        long startedAt = System.nanoTime();

        try {
            Mono<NewsPageResponse> request = webClient.get()
                    .uri(uriBuilder -> buildUri(uriBuilder, limit, offset, search, ordering, newsSite))
                    .headers(headers -> addHeaders(headers, requestId, traceContext))
                    .exchangeToMono(response -> decodeResponse(
                            response,
                            requestId,
                            callerSpan,
                            cacheStatus,
                            rateLimitRemaining));

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

            NewsPageResponse result = request
                    .timeout(properties.requestTimeout(), Mono.error(NewsException.timeout(null)))
                    .block();
            if (result == null) {
                throw NewsException.invalidResponse(null);
            }

            callerSpan.setAttribute("news.retry.count", attempts.get() - 1L);
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.info("news request completed operation=get limit={} offset={} search_supplied={} site_supplied={} attempts={} duration_ms={} cache={} rate_limit_remaining={} status=OK",
                        limit, offset, search != null, newsSite != null, attempts.get(), elapsedMillis(startedAt),
                        cacheStatus.get(), rateLimitRemaining.get());
            }
            return result;
        } catch (RuntimeException exception) {
            NewsException mapped = mapException(exception);
            callerSpan.setAttribute("news.retry.count", attempts.get() - 1L);
            callerSpan.setAttribute("error.type", mapped.code());
            try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
                logger.warn("news request failed operation=get limit={} offset={} search_supplied={} site_supplied={} attempts={} duration_ms={} cache={} rate_limit_remaining={} status=ERROR code={} upstream_status={}",
                        limit, offset, search != null, newsSite != null, attempts.get(), elapsedMillis(startedAt),
                        cacheStatus.get(), rateLimitRemaining.get(), mapped.code(), mapped.httpStatus());
            }
            throw mapped;
        }
    }

    private static URI buildUri(
            UriBuilder uriBuilder,
            int limit,
            int offset,
            String search,
            String ordering,
            String newsSite) {
        uriBuilder.path("/news")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .queryParam("ordering", ordering);
        if (search != null) {
            uriBuilder.queryParam("search", search);
        }
        if (newsSite != null) {
            uriBuilder.queryParam("news_site", newsSite);
        }
        return uriBuilder.build();
    }

    private void addHeaders(HttpHeaders headers, String requestId, Context traceContext) {
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        openTelemetry.getPropagators().getTextMapPropagator()
                .inject(traceContext, headers, HttpHeaders::set);
    }

    private Mono<NewsPageResponse> decodeResponse(
            ClientResponse response,
            String requestId,
            Span callerSpan,
            AtomicReference<String> cacheStatus,
            AtomicLong rateLimitRemaining) {
        HttpStatusCode status = response.statusCode();
        HttpHeaders headers = response.headers().asHttpHeaders();
        captureResponseMetadata(headers, requestId, callerSpan, cacheStatus, rateLimitRemaining);
        callerSpan.setAttribute("news.upstream.status_code", status.value());

        if (status.is2xxSuccessful()) {
            return response.bodyToMono(NewsPageResponse.class)
                    .onErrorMap(exception -> !isTransportFailure(exception), NewsException::invalidResponse)
                    .switchIfEmpty(Mono.error(NewsException.invalidResponse(null)));
        }

        Long retryAfterSeconds = parseBoundedLong(headers.getFirst(HttpHeaders.RETRY_AFTER), MAX_RETRY_AFTER_SECONDS);
        return response.bodyToMono(NewsErrorResponse.class)
                .onErrorResume(exception -> isTransportFailure(exception)
                        ? Mono.error(exception)
                        : Mono.just(NewsErrorResponse.empty()))
                .defaultIfEmpty(NewsErrorResponse.empty())
                .flatMap(body -> Mono.error(NewsException.upstream(
                        body.errorCode(), fallbackCode(status.value()), status.value(), retryAfterSeconds)));
    }

    private static void captureResponseMetadata(
            HttpHeaders headers,
            String requestId,
            Span callerSpan,
            AtomicReference<String> cacheStatus,
            AtomicLong rateLimitRemaining) {
        String cache = boundedToken(headers.getFirst("X-Cache"));
        Long remaining = parseBoundedLong(headers.getFirst("RateLimit-Remaining"), Integer.MAX_VALUE);
        String responseRequestId = headers.getFirst(RequestIdFilter.HEADER_NAME);
        cacheStatus.set(cache);
        rateLimitRemaining.set(remaining == null ? -1 : remaining);
        callerSpan.setAttribute("news.cache.status", cache);
        callerSpan.setAttribute("news.rate_limit.remaining", rateLimitRemaining.get());
        callerSpan.setAttribute("news.response.request_id_present", responseRequestId != null);
        if (responseRequestId != null) {
            callerSpan.setAttribute("news.response.request_id_matches", responseRequestId.equals(requestId));
        }
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        return isTransportFailure(unwrapped)
                || unwrapped instanceof NewsException exception && exception.retryable();
    }

    private static NewsException mapException(Throwable throwable) {
        Throwable unwrapped = Exceptions.unwrap(throwable);
        if (unwrapped instanceof NewsException newsException) {
            if ("NEWS_INVALID_RESPONSE".equals(newsException.code()) && isTimeout(newsException)) {
                return NewsException.timeout(newsException.getCause());
            }
            return newsException;
        }
        if (isTimeout(unwrapped)) {
            return NewsException.timeout(unwrapped);
        }
        return NewsException.unavailable(unwrapped);
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
            case 502, 503 -> "NEWS_UNAVAILABLE";
            default -> "NEWS_UPSTREAM_ERROR";
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

    private static Duration maxBackoff(Duration initialBackoff) {
        Duration candidate = initialBackoff.multipliedBy(4);
        return candidate.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : candidate;
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
