package xyz.arryan.livia.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesAValidRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "edge-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (_request, _response) ->
                seenInChain.set((String) request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE)));

        assertThat(seenInChain).hasValue("edge-request-123");
        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("edge-request-123");
    }

    @Test
    void replacesAnInvalidRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "bad request id\nsecret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (_request, _response) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
