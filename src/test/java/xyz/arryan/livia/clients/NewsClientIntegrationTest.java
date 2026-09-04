package xyz.arryan.livia.clients;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.clients.dto.NewsPageResponse;
import xyz.arryan.livia.config.NewsClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.NewsException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsClientIntegrationTest {

    private static final String SUCCESS_BODY = """
            {
              "total_count": 35942,
              "limit": 24,
              "offset": 0,
              "has_next_page": true,
              "has_previous_page": false,
              "articles": [{
                "id": 39822,
                "title": "Example title",
                "summary": "Example summary",
                "url": "https://publisher.example/article",
                "image_url": "https://publisher.example/image.jpg",
                "news_site": "NASA",
                "authors": ["Author Name"],
                "published_at": "2026-09-03T15:59:48Z",
                "updated_at": "2026-09-03T16:00:00Z",
                "featured": false,
                "launch_ids": ["123e4567-e89b-12d3-a456-426614174000"],
                "event_ids": [42]
              }]
            }
            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        RequestContextHolder.resetRequestAttributes();
        server.shutdown();
    }

    @Test
    void sendsDefaultsWithoutOptionalValuesOrAuthorizationAndMapsEveryField() throws Exception {
        server.enqueue(newsJson(200, SUCCESS_BODY));
        setRequestId("news-request-123");

        NewsPageResponse result = client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null);

        assertThat(result.totalCount()).isEqualTo(35_942);
        assertThat(result.hasNextPage()).isTrue();
        assertThat(result.hasPreviousPage()).isFalse();
        assertThat(result.articles()).hasSize(1);
        assertThat(result.articles().getFirst().id()).isEqualTo(39_822L);
        assertThat(result.articles().getFirst().imageUrl()).isEqualTo("https://publisher.example/image.jpg");
        assertThat(result.articles().getFirst().newsSite()).isEqualTo("NASA");
        assertThat(result.articles().getFirst().authors()).containsExactly("Author Name");
        assertThat(result.articles().getFirst().publishedAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-03T15:59:48Z"));
        assertThat(result.articles().getFirst().launchIds())
                .containsExactly("123e4567-e89b-12d3-a456-426614174000");
        assertThat(result.articles().getFirst().eventIds()).containsExactly(42L);

        RecordedRequest request = takeRequest();
        HttpUrl url = request.getRequestUrl();
        assertThat(url.encodedPath()).isEqualTo("/news");
        assertThat(url.queryParameter("limit")).isEqualTo("24");
        assertThat(url.queryParameter("offset")).isEqualTo("0");
        assertThat(url.queryParameter("ordering")).isEqualTo("-published_at");
        assertThat(url.queryParameter("search")).isNull();
        assertThat(url.queryParameter("news_site")).isNull();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("news-request-123");
    }

    @Test
    void mapsPaginationSearchOrderingAndNewsSiteExactly() throws Exception {
        server.enqueue(newsJson(200, SUCCESS_BODY));

        client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(50, 100, "Moon landing", "updated_at", "NASA,ESA");

        HttpUrl url = takeRequest().getRequestUrl();
        assertThat(url.queryParameter("limit")).isEqualTo("50");
        assertThat(url.queryParameter("offset")).isEqualTo("100");
        assertThat(url.queryParameter("search")).isEqualTo("Moon landing");
        assertThat(url.queryParameter("ordering")).isEqualTo("updated_at");
        assertThat(url.queryParameter("news_site")).isEqualTo("NASA,ESA");
    }

    @Test
    void translatesStructuredErrorsWithoutLeakingTheServiceMessage() {
        server.enqueue(newsJson(502, """
                {"error":{"code":"PROVIDER_UNAVAILABLE","message":"secret at http://provider.internal"}}
                """));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null))
                .isInstanceOfSatisfying(NewsException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.httpStatus()).isEqualTo(502);
                    assertThat(exception.getMessage()).doesNotContain("secret", "http://provider.internal");
                });
    }

    @Test
    void normalizesFastApiValidationErrors() {
        server.enqueue(newsJson(422, """
                {"detail":[{"type":"greater_than_equal","loc":["query","offset"],"msg":"bad"}]}
                """));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 3, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null))
                .isInstanceOfSatisfying(NewsException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_QUERY");
                    assertThat(exception.httpStatus()).isEqualTo(422);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void doesNotRetryRateLimitsAndPreservesBoundedRetryAfter() {
        server.enqueue(newsJson(429, """
                {"error":{"code":"RATE_LIMITED","message":"busy"}}
                """).setHeader("Retry-After", "60"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 3, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null))
                .isInstanceOfSatisfying(NewsException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.httpStatus()).isEqualTo(429);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(60L);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesOneSelectedTransientResponseWithinTheBound() {
        server.enqueue(newsJson(502, """
                {"error":{"code":"PROVIDER_UNAVAILABLE","message":"temporarily unavailable"}}
                """));
        server.enqueue(newsJson(200, SUCCESS_BODY));

        NewsPageResponse result = client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null);

        assertThat(result.articles()).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesATransportFailureWhileReadingASuccessBody() {
        server.enqueue(newsJson(200, SUCCESS_BODY)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(newsJson(200, SUCCESS_BODY));

        NewsPageResponse result = client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null);

        assertThat(result.articles()).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void mapsMalformedSuccessJsonSeparately() {
        server.enqueue(newsJson(200, "{not-json"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null))
                .isInstanceOfSatisfying(NewsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("NEWS_INVALID_RESPONSE"));
    }

    @Test
    void enforcesTheOverallRequestTimeout() {
        server.enqueue(newsJson(200, SUCCESS_BODY).setBodyDelay(500, TimeUnit.MILLISECONDS));

        assertThatThrownBy(() -> client(Duration.ofMillis(50), 1, OpenTelemetry.noop())
                .get(24, 0, null, "-published_at", null))
                .isInstanceOfSatisfying(NewsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("NEWS_TIMEOUT"));
    }

    @Test
    void propagatesW3cTraceContextAndTraceState() throws Exception {
        server.enqueue(newsJson(200, SUCCESS_BODY));
        OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        Span incomingSpan = Span.wrap(SpanContext.createFromRemoteParent(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.builder().put("cosmofy", "edge").build()));

        try (Scope ignored = incomingSpan.makeCurrent()) {
            client(Duration.ofSeconds(2), 1, telemetry)
                    .get(24, 0, null, "-published_at", null);
        }

        RecordedRequest request = takeRequest();
        assertThat(request.getHeader("traceparent"))
                .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        assertThat(request.getHeader("tracestate")).isEqualTo("cosmofy=edge");
    }

    private NewsClient client(Duration requestTimeout, int maxAttempts, OpenTelemetry telemetry) {
        URI baseUrl = server.url("/").uri();
        NewsClientProperties properties = new NewsClientProperties(
                baseUrl,
                Duration.ofSeconds(1),
                requestTimeout,
                maxAttempts,
                Duration.ofMillis(10));
        return new NewsClient(WebClient.builder().build(), properties, telemetry);
    }

    private void setRequestId(String requestId) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, requestId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static MockResponse newsJson(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Cache", "HIT")
                .setHeader("RateLimit-Remaining", "99")
                .setHeader("x-request-id", "news-response-123")
                .setBody(body);
    }
}
