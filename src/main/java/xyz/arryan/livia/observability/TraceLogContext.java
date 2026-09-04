package xyz.arryan.livia.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public final class TraceLogContext implements AutoCloseable {

    private final Map<String, String> previousValues = new HashMap<>();

    private TraceLogContext(String requestId) {
        put("request_id", requestId);

        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            put("trace_id", spanContext.getTraceId());
            put("span_id", spanContext.getSpanId());
        }
    }

    public static TraceLogContext open(String requestId) {
        return new TraceLogContext(requestId);
    }

    private void put(String key, String value) {
        previousValues.put(key, MDC.get(key));
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    @Override
    public void close() {
        previousValues.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
