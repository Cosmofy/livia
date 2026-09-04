package xyz.arryan.livia.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.arryan.livia.observability.TraceLogContext;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "x-request-id";
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String requestId = validOrNew(request.getHeader(HEADER_NAME));
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(HEADER_NAME, requestId);

        try (TraceLogContext ignored = TraceLogContext.open(requestId)) {
            filterChain.doFilter(request, response);
        }
    }

    public static String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object requestId = attributes.getRequest().getAttribute(REQUEST_ATTRIBUTE);
            if (requestId instanceof String value && VALID_REQUEST_ID.matcher(value).matches()) {
                return value;
            }
        }

        String mdcRequestId = MDC.get("request_id");
        return mdcRequestId != null && VALID_REQUEST_ID.matcher(mdcRequestId).matches()
                ? mdcRequestId
                : UUID.randomUUID().toString();
    }

    static String validOrNew(String candidate) {
        return candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }

}
