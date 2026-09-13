package xyz.arryan.livia.clients;

import io.netty.channel.ChannelOption;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import xyz.arryan.livia.clients.dto.NatureErrorResponse;
import xyz.arryan.livia.clients.dto.NatureEventResponse;
import xyz.arryan.livia.config.NatureClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.NatureException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class NatureClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final WebClient webClient;
    private final NatureClientProperties properties;
    private final OpenTelemetry openTelemetry;

    @Autowired
    public NatureClient(WebClient sharedWebClient, NatureClientProperties properties) {
        this(sharedWebClient, properties, GlobalOpenTelemetry.get());
    }

    NatureClient(WebClient sharedWebClient, NatureClientProperties properties, OpenTelemetry openTelemetry) {
        this.properties = properties;
        this.openTelemetry = openTelemetry;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout().toMillis()))
                .responseTimeout(properties.requestTimeout());
        this.webClient = sharedWebClient.mutate()
                .baseUrl(properties.baseUrl().toString().replaceAll("/+$", ""))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    public List<NatureEventResponse> events(int days) {
        Context traceContext = Context.current();
        String requestId = RequestIdFilter.currentRequestId();
        Mono<List<NatureEventResponse>> request = webClient.get().uri(uri -> uri.path("/events").queryParam("days", days).build())
                .headers(headers -> addHeaders(headers, requestId, traceContext))
                .exchangeToMono(response -> decode(response.statusCode(), response))
                .timeout(properties.requestTimeout(), Mono.error(NatureException.timeout(null)));
        if (properties.maxAttempts() > 1) {
            request = request.retryWhen(Retry.backoff(properties.maxAttempts() - 1L, properties.retryBackoff())
                    .filter(this::isRetryable).onRetryExhaustedThrow((_retry, signal) -> signal.failure()));
        }
        try {
            List<NatureEventResponse> result = request.block();
            if (result == null) throw NatureException.invalidResponse(null);
            return result;
        } catch (RuntimeException exception) {
            Throwable cause = Exceptions.unwrap(exception);
            if (cause instanceof NatureException natureException) throw natureException;
            if (isTimeout(cause)) throw NatureException.timeout(cause);
            throw NatureException.unavailable(cause);
        }
    }

    private Mono<List<NatureEventResponse>> decode(HttpStatusCode status, ClientResponse response) {
        if (status.is2xxSuccessful()) {
            return response.bodyToFlux(NatureEventResponse.class).collectList()
                    .onErrorMap(exception -> !isTransportFailure(exception), NatureException::invalidResponse);
        }
        Long retryAfter = parseRetryAfter(response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        return response.bodyToMono(NatureErrorResponse.class).onErrorReturn(NatureErrorResponse.empty())
                .defaultIfEmpty(NatureErrorResponse.empty())
                .flatMap(body -> Mono.error(NatureException.upstream(body.code(), status.value(), retryAfter)));
    }

    private void addHeaders(HttpHeaders headers, String requestId, Context traceContext) {
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        openTelemetry.getPropagators().getTextMapPropagator().inject(traceContext, headers, HttpHeaders::set);
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable cause = Exceptions.unwrap(throwable);
        return isTransportFailure(cause) || cause instanceof NatureException exception && exception.retryable();
    }

    private static boolean isTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WebClientRequestException || current instanceof TimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable throwable) {
        return throwable instanceof TimeoutException || throwable != null && throwable.getClass().getSimpleName().contains("Timeout");
    }

    private static Long parseRetryAfter(String value) {
        if (value == null) return null;
        try { long seconds = Long.parseLong(value); return seconds >= 0 ? seconds : null; }
        catch (NumberFormatException ignored) { return null; }
    }
}
