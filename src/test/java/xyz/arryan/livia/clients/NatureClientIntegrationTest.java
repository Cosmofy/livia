package xyz.arryan.livia.clients;

import io.opentelemetry.api.OpenTelemetry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.config.NatureClientProperties;
import xyz.arryan.livia.config.RequestIdFilter;
import xyz.arryan.livia.errors.NatureException;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NatureClientIntegrationTest {
    private MockWebServer server;

    @BeforeEach void setUp() throws IOException { server = new MockWebServer(); server.start(); }
    @AfterEach void tearDown() throws IOException { RequestContextHolder.resetRequestAttributes(); server.shutdown(); }

    @Test
    void forwardsDaysRequestIdAndMapsTheEventShape() throws Exception {
        server.enqueue(json(200, """
                [{"id":"EONET_1","title":"Storm","categories":[{"id":"severeStorms","title":"Severe Storms"}],
                "sources":[{"id":"JTWC","url":"https://example.test"}],"geometry":[{"type":"Point","coordinates":[-117.3,16.4]}]}]
                """));
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(RequestIdFilter.REQUEST_ATTRIBUTE, "request-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        var result = client(2).events(0);

        assertThat(result).singleElement().satisfies(event -> assertThat(event.geometry()).singleElement()
                .satisfies(geometry -> assertThat(geometry.coordinates()).containsExactly(-117.3, 16.4)));
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/events?days=0");
        assertThat(request.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("request-123");
    }

    @Test
    void mapsNatureInputErrorsWithoutRetrying() {
        server.enqueue(json(422, "{" + "\"error\":{\"code\":\"UNSUPPORTED_DATE_RANGE\"}}"));

        assertThatThrownBy(() -> client(2).events(2000)).isInstanceOfSatisfying(NatureException.class,
                error -> assertThat(error.code()).isEqualTo("UNSUPPORTED_DATE_RANGE"));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesA502EonetFailure() {
        server.enqueue(json(502, "{" + "\"error\":{\"code\":\"EONET_UNAVAILABLE\"}}"));
        server.enqueue(json(200, "[]"));

        assertThat(client(2).events(14)).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    private NatureClient client(int attempts) {
        NatureClientProperties properties = new NatureClientProperties(server.url("/").uri(), Duration.ofSeconds(1),
                Duration.ofSeconds(3), attempts, Duration.ofMillis(1));
        return new NatureClient(WebClient.builder().build(), properties, OpenTelemetry.noop());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
