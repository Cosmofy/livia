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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.config.ApodClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.mappers.ApodMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApodClientIntegrationTest {

    private static final String SUCCESS_BODY = """
            {
              "date": "2026-09-04",
              "title": "A title",
              "explanation": "An explanation",
              "media_type": "video",
              "url": "https://example.com/apod-video",
              "hdurl": null,
              "credit": null,
              "copyright": "A copyright"
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
    void omittedDateUsesBareApodPathAndSendsNoAuthorization() throws Exception {
        server.enqueue(json(200, SUCCESS_BODY));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        ApodResponse result = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(null);

        assertThat(result.date()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(result.mediaType()).isEqualTo("video");
        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/apod");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("request-123");
    }

    @Test
    void exactDateProducesTheExpectedQueryParameter() throws Exception {
        server.enqueue(json(200, SUCCESS_BODY));

        client(Duration.ofSeconds(2), Duration.ofSeconds(2), 2, OpenTelemetry.noop())
                .get(LocalDate.of(2026, 9, 4));

        assertThat(takeRequest().getPath()).isEqualTo("/apod?date=2026-09-04");
    }

    @Test
    void searchSendsTextAndBoundedLimitAndDecodesRankedResults() throws Exception {
        server.enqueue(json(200, """
                {
                  "query":"spiral galaxy",
                  "search_mode":"hybrid",
                  "results":[{
                    "date":"1997-04-19",
                    "title":"Spiral Galaxy M83",
                    "explanation":"Explanation",
                    "media_type":"image",
                    "url":"",
                    "hdurl":null,
                    "credit":null,
                    "copyright":null,
                    "relevance_score":1.0,
                    "match_types":["lexical","semantic"]
                  }]
                }
                """));

        ApodSearchResponse result = client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .search("spiral galaxy", 5);

        assertThat(result.searchMode()).isEqualTo("hybrid");
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().relevanceScore()).isEqualTo(1.0);
        assertThat(takeRequest().getPath()).isEqualTo("/vector/search?q=spiral%20galaxy&limit=5");
    }

    @Test
    void translatesStructuredErrorsWithoutLeakingTheUpstreamMessage() {
        server.enqueue(json(404, """
                {"error":{"code":"NOT_FOUND","message":"secret at http://apod.internal"}}
                """));

        assertThatThrownBy(() -> client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, OpenTelemetry.noop()).get(null))
                .isInstanceOfSatisfying(ApodException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("NOT_FOUND");
                    assertThat(exception.httpStatus()).isEqualTo(404);
                    assertThat(exception.getMessage()).doesNotContain("secret", "http://apod.internal");
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void mapsStandardFastApiValidationErrorsByOperation() {
        server.enqueue(json(422, """
                {"detail":[{"type":"missing","loc":["query","q"],"msg":"Field required"}]}
                """));

        assertThatThrownBy(() -> client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, OpenTelemetry.noop()).search("galaxy", 5))
                .isInstanceOfSatisfying(ApodException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("INVALID_SEARCH_QUERY");
                    assertThat(exception.httpStatus()).isEqualTo(422);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void malformedSuccessJsonIsNotTreatedAsADomainError() {
        server.enqueue(json(200, "{not-json"));

        assertThatThrownBy(() -> client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 1, OpenTelemetry.noop()).get(null))
                .isInstanceOfSatisfying(ApodException.class,
                        exception -> assertThat(exception.code()).isEqualTo("APOD_INVALID_RESPONSE"));
    }

    @Test
    void retriesOneSelectedTransientResponseWithinTheBound() {
        server.enqueue(json(502, """
                {"error":{"code":"NASA_UNAVAILABLE","message":"temporarily unavailable"}}
                """));
        server.enqueue(json(200, SUCCESS_BODY));

        ApodResponse result = client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2, OpenTelemetry.noop()).get(null);

        assertThat(result.title()).isEqualTo("A title");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesATransportFailureWhileReadingASuccessBody() {
        server.enqueue(json(200, SUCCESS_BODY)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(json(200, SUCCESS_BODY));

        ApodResponse result = client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2, OpenTelemetry.noop()).get(null);

        assertThat(result.title()).isEqualTo("A title");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void doesNotRetryRateLimits() {
        server.enqueue(json(503, """
                {"error":{"code":"NASA_RATE_LIMITED","message":"busy"}}
                """));
        assertThatThrownBy(() -> client(
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, OpenTelemetry.noop()).get(null))
                .isInstanceOf(ApodException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void enforcesTheOverallRequestTimeout() {
        server.enqueue(json(200, SUCCESS_BODY).setBodyDelay(500, TimeUnit.MILLISECONDS));

        assertThatThrownBy(() -> client(
                Duration.ofMillis(50), Duration.ofSeconds(2), 1, OpenTelemetry.noop()).get(null))
                .isInstanceOfSatisfying(ApodException.class,
                        exception -> assertThat(exception.code()).isEqualTo("APOD_TIMEOUT"));
    }

    @Test
    void propagatesW3cTraceContextWithoutCreatingAManualClientSpan() throws Exception {
        server.enqueue(json(200, SUCCESS_BODY));
        OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        Span incomingSpan = Span.wrap(SpanContext.createFromRemoteParent(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                TraceFlags.getSampled(),
                TraceState.builder().put("cosmofy", "edge").build()));

        try (Scope ignored = incomingSpan.makeCurrent()) {
            client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1, telemetry).get(null);
        }

        RecordedRequest request = takeRequest();
        assertThat(request.getHeader("traceparent"))
                .isEqualTo("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        assertThat(request.getHeader("tracestate")).isEqualTo("cosmofy=edge");
    }

    private ApodClient client(
            Duration apodTimeout,
            Duration searchTimeout,
            int maxAttempts,
            OpenTelemetry telemetry) {
        URI baseUrl = server.url("/").uri();
        ApodClientProperties properties = new ApodClientProperties(
                baseUrl,
                Duration.ofSeconds(1),
                apodTimeout,
                searchTimeout,
                maxAttempts,
                Duration.ofMillis(10));
        return new ApodClient(WebClient.builder().build(), properties, telemetry);
    }

    @Test
    void similarityUsesTheAgreedRouteAndDecodesFlatResults() throws Exception {
        server.enqueue(json(200, """
                {"date":"2024-02-29","results":[{
                  "date":"2024-01-01","title":"Related","explanation":"Explanation",
                  "media_type":"image","url":"","hdurl":null,"credit":null,"copyright":null,
                  "relevance_score":0.8
                }]}
                """));
        var result = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1, OpenTelemetry.noop())
                .similar(LocalDate.of(2024, 2, 29), 5);
        assertThat(result.date()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(result.results().getFirst().relevanceScore()).isEqualTo(0.8);
        RecordedRequest request = takeRequest();
        assertThat(request.getPath()).isEqualTo("/vector/similar?date=2024-02-29&limit=5");
        assertThat(request.getHeader("x-request-id")).isNotBlank();
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void distinguishesAnUndeployedSimilarityRouteFromAMissingPicture() {
        server.enqueue(json(404, "{\"detail\":\"Not Found\"}"));
        var client = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 2, OpenTelemetry.noop());
        assertThatThrownBy(() -> client.similar(LocalDate.of(2024, 2, 29), 5))
                .isInstanceOfSatisfying(ApodException.class,
                        error -> assertThat(error.code()).isEqualTo("SIMILARITY_UNAVAILABLE"));
        server.enqueue(json(404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"internal\"}}"));
        assertThatThrownBy(() -> client.similar(LocalDate.of(2024, 2, 29), 5))
                .isInstanceOfSatisfying(ApodException.class,
                        error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"https://apod.nasa.gov/apod/image/source.jpg\""})
    void mapsFallbackAcrossEveryEndpointIncludingNullAndOlderResponses(String fallbackJson) {
        String body = SUCCESS_BODY.replace("\"copyright\": \"A copyright\"",
                "\"copyright\": \"A copyright\", \"s3_object_key\": \"internal-only\""
                        + (fallbackJson == null ? "" : ", \"fallback_url\": " + fallbackJson));
        String expectedFallback = fallbackJson == null || "null".equals(fallbackJson)
                ? null : "https://apod.nasa.gov/apod/image/source.jpg";
        String expectedUrl = "https://example.com/apod-video";
        if (expectedFallback != null) {
            expectedUrl = "https://media.example/hd/verified.jpg";
            body = body.replace("https://example.com/apod-video", expectedUrl)
                    .replace("\"hdurl\": null", "\"hdurl\": \"" + expectedUrl + "\"")
                    .replace("\"media_type\": \"video\"", "\"media_type\": \"image\"");
        }
        String rankedBody = body.substring(0, body.lastIndexOf('}'))
                + ", \"relevance_score\": 0.8, \"match_types\": [\"semantic\"] }";
        server.enqueue(json(200, body));
        server.enqueue(json(200, "{\"query\":\"galaxy\",\"search_mode\":\"semantic\",\"results\":[" + rankedBody + "]}"));
        server.enqueue(json(200, "{\"date\":\"2024-02-29\",\"results\":[" + rankedBody + "]}"));

        var client = client(Duration.ofSeconds(2), Duration.ofSeconds(2), 1, OpenTelemetry.noop());
        var mapper = new ApodMapper();
        var legacy = mapper.toGraphQl(client.get(null));
        var picture = mapper.toPicture(legacy);
        var search = mapper.toGraphQl(client.search("galaxy", 1)).getResults().getFirst();
        var similar = mapper.toGraphQl(client.similar(LocalDate.of(2024, 2, 29), 1)).getResults().getFirst();

        assertThat(legacy.getFallbackUrl()).isEqualTo(expectedFallback);
        assertThat(picture.getFallbackUrl()).isEqualTo(expectedFallback);
        assertThat(search.getFallbackUrl()).isEqualTo(expectedFallback);
        assertThat(similar.getFallbackUrl()).isEqualTo(expectedFallback);
        assertThat(legacy.getUrl()).isEqualTo(expectedUrl);
        assertThat(picture.getUrl()).isEqualTo(expectedUrl);
        assertThat(search.getUrl()).isEqualTo(expectedUrl);
        assertThat(similar.getUrl()).isEqualTo(expectedUrl);
        String expectedHdUrl = expectedFallback == null ? null : expectedUrl;
        assertThat(picture.getHdUrl()).isEqualTo(expectedHdUrl);
        assertThat(search.getHdUrl()).isEqualTo(expectedHdUrl);
        assertThat(similar.getHdUrl()).isEqualTo(expectedHdUrl);
        assertThat(similar.getMediaType()).isEqualTo(expectedFallback == null ? "video" : "image");
        assertThat(similar.getCopyright()).isEqualTo("A copyright");
        assertThat(search.getRelevanceScore()).isEqualTo(0.8);
        assertThat(similar.getRelevanceScore()).isEqualTo(0.8);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
