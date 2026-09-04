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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.config.ArticlesClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ArticlesException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticlesClientIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("e265a685-c093-5097-9337-fb1cdc73936c");
    private static final String ARTICLE_BODY = """
            {
              "id": "e265a685-c093-5097-9337-fb1cdc73936c",
              "month": 8,
              "year": 2026,
              "title": "Example title",
              "subtitle": "Example subtitle",
              "url": "https://publisher.example/article",
              "source": "Quanta Magazine",
              "banner": {
                "image": "https://publisher.example/banner.jpg",
                "designer": "Designer Name"
              },
              "authors": [{
                "name": "Author Name",
                "title": "Staff Writer",
                "image": "https://publisher.example/author.jpg"
              }]
            }
            """;
    private static final String PAGE_BODY = """
            {
              "total_count": 27,
              "limit": 24,
              "offset": 0,
              "has_next_page": true,
              "has_previous_page": false,
              "articles": [%s]
            }
            """.formatted(ARTICLE_BODY);

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
        server.enqueue(articlesJson(200, PAGE_BODY));
        setRequestId("articles-request-123");

        ArticlePageResponse result = client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date");

        assertThat(result.totalCount()).isEqualTo(27);
        assertThat(result.hasNextPage()).isTrue();
        assertThat(result.articles()).singleElement().satisfies(article -> {
            assertThat(article.id()).isEqualTo(ARTICLE_ID);
            assertThat(article.banner().designer()).isEqualTo("Designer Name");
            assertThat(article.authors()).singleElement()
                    .satisfies(author -> assertThat(author.name()).isEqualTo("Author Name"));
        });

        RecordedRequest request = takeRequest();
        HttpUrl url = request.getRequestUrl();
        assertThat(url.encodedPath()).isEqualTo("/articles");
        assertThat(url.queryParameter("limit")).isEqualTo("24");
        assertThat(url.queryParameter("offset")).isEqualTo("0");
        assertThat(url.queryParameter("ordering")).isEqualTo("-date");
        assertThat(url.queryParameter("search")).isNull();
        assertThat(url.queryParameter("year")).isNull();
        assertThat(url.queryParameter("month")).isNull();
        assertThat(url.queryParameter("source")).isNull();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("articles-request-123");
    }

    @Test
    void mapsPaginationAndEveryFilterExactly() throws Exception {
        server.enqueue(articlesJson(200, PAGE_BODY));

        client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(50, 100, "dark matter", 2026, 8, "Quanta Magazine", "title");

        HttpUrl url = takeRequest().getRequestUrl();
        assertThat(url.queryParameter("limit")).isEqualTo("50");
        assertThat(url.queryParameter("offset")).isEqualTo("100");
        assertThat(url.queryParameter("search")).isEqualTo("dark matter");
        assertThat(url.queryParameter("year")).isEqualTo("2026");
        assertThat(url.queryParameter("month")).isEqualTo("8");
        assertThat(url.queryParameter("source")).isEqualTo("Quanta Magazine");
        assertThat(url.queryParameter("ordering")).isEqualTo("title");
    }

    @Test
    void usesTheExactArticleRouteWithoutACollectionScan() throws Exception {
        server.enqueue(articlesJson(200, ARTICLE_BODY).setHeader("X-Cache", "MEMORY"));

        ArticleResponse result = client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .getById(ARTICLE_ID);

        assertThat(result.id()).isEqualTo(ARTICLE_ID);
        assertThat(takeRequest().getPath()).isEqualTo("/articles/" + ARTICLE_ID);
    }

    @ParameterizedTest
    @CsvSource({
            "422,INVALID_QUERY",
            "404,ARTICLE_NOT_FOUND",
            "429,RATE_LIMITED",
            "503,INVALID_DATASET",
            "500,INTERNAL_ERROR"
    })
    void mapsEveryKnownStructuredError(int status, String code) {
        server.enqueue(articlesJson(status,
                "{\"error\":{\"code\":\"" + code + "\",\"message\":\"internal detail\"}}"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date"))
                .isInstanceOfSatisfying(ArticlesException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.httpStatus()).isEqualTo(status);
                    assertThat(exception.getMessage()).doesNotContain("internal detail");
                });
    }

    @Test
    void normalizesFastApiValidationErrorsAndDoesNotRetry() {
        server.enqueue(articlesJson(422, """
                {"detail":[{"type":"greater_than_equal","loc":["query","offset"],"msg":"bad"}]}
                """));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 3, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date"))
                .isInstanceOfSatisfying(ArticlesException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_QUERY"));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void respectsRetryAfterAndDoesNotRetryRateLimits() {
        server.enqueue(articlesJson(429, """
                {"error":{"code":"RATE_LIMITED","message":"busy"}}
                """).setHeader("Retry-After", "60"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 3, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date"))
                .isInstanceOfSatisfying(ArticlesException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.retryAfterSeconds()).isEqualTo(60L);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesOneSelectedTransientResponseWithinTheBound() {
        server.enqueue(articlesJson(503, """
                {"error":{"code":"INVALID_DATASET","message":"temporarily unavailable"}}
                """));
        server.enqueue(articlesJson(200, PAGE_BODY));

        ArticlePageResponse result = client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date");

        assertThat(result.articles()).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesATransportFailureWhileReadingASuccessBody() {
        server.enqueue(articlesJson(200, PAGE_BODY)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(articlesJson(200, PAGE_BODY));

        assertThat(client(Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date").articles()).hasSize(1);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void mapsMalformedSuccessJsonSeparately() {
        server.enqueue(articlesJson(200, "{not-json"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date"))
                .isInstanceOfSatisfying(ArticlesException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLES_INVALID_RESPONSE"));
    }

    @Test
    void enforcesTheOverallRequestTimeout() {
        server.enqueue(articlesJson(200, PAGE_BODY).setBodyDelay(500, TimeUnit.MILLISECONDS));

        assertThatThrownBy(() -> client(Duration.ofMillis(50), 1, OpenTelemetry.noop())
                .get(24, 0, null, null, null, null, "-date"))
                .isInstanceOfSatisfying(ArticlesException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLES_TIMEOUT"));
    }

    @Test
    void propagatesW3cTraceContextAndTraceState() throws Exception {
        server.enqueue(articlesJson(200, PAGE_BODY));
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
                    .get(24, 0, null, null, null, null, "-date");
        }

        RecordedRequest request = takeRequest();
        assertThat(request.getHeader("traceparent"))
                .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        assertThat(request.getHeader("tracestate")).isEqualTo("cosmofy=edge");
    }

    private ArticlesClient client(Duration requestTimeout, int maxAttempts, OpenTelemetry telemetry) {
        URI baseUrl = server.url("/").uri();
        ArticlesClientProperties properties = new ArticlesClientProperties(
                baseUrl,
                Duration.ofSeconds(1),
                requestTimeout,
                maxAttempts,
                Duration.ofMillis(10));
        return new ArticlesClient(WebClient.builder().build(), properties, telemetry);
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

    private static MockResponse articlesJson(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Cache", "HIT")
                .setHeader("RateLimit-Limit", "100")
                .setHeader("RateLimit-Remaining", "99")
                .setHeader("RateLimit-Reset", "1788480000")
                .setHeader("x-request-id", "articles-response-123")
                .setBody(body);
    }
}
