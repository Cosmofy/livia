package xyz.arryan.livia.errors;

import java.util.Map;
import java.util.Set;

public class NatureException extends RuntimeException {

    private static final Set<String> UPSTREAM_CODES = Set.of(
            "INVALID_QUERY", "UNSUPPORTED_DATE_RANGE", "EONET_UNAVAILABLE",
            "INVALID_EONET_RESPONSE", "EONET_RATE_LIMITED");
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("INVALID_EVENTS_QUERY", "daysInput must be zero or greater."),
            Map.entry("UNSUPPORTED_DATE_RANGE", "The requested event window begins before January 1, 2022."),
            Map.entry("EONET_UNAVAILABLE", "NASA EONET is temporarily unavailable."),
            Map.entry("INVALID_EONET_RESPONSE", "NASA EONET returned an invalid response."),
            Map.entry("EONET_RATE_LIMITED", "NASA EONET is temporarily rate limited."),
            Map.entry("NATURE_TIMEOUT", "The Nature service did not respond in time."),
            Map.entry("NATURE_UNAVAILABLE", "The Nature service is temporarily unavailable."),
            Map.entry("NATURE_UPSTREAM_ERROR", "The Nature service could not complete the request."));

    private final String code;
    private final Integer httpStatus;
    private final Long retryAfterSeconds;
    private final boolean retryable;

    private NatureException(String code, Integer httpStatus, Long retryAfterSeconds, boolean retryable, Throwable cause) {
        super(MESSAGES.getOrDefault(code, MESSAGES.get("NATURE_UPSTREAM_ERROR")), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
        this.retryable = retryable;
    }

    public static NatureException validation(String code) {
        return new NatureException(code, null, null, false, null);
    }

    public static NatureException upstream(String upstreamCode, int status, Long retryAfterSeconds) {
        String code = UPSTREAM_CODES.contains(upstreamCode) ? upstreamCode : "NATURE_UPSTREAM_ERROR";
        return new NatureException(code, status, retryAfterSeconds,
                (status == 502 && !"INVALID_EONET_RESPONSE".equals(code)), null);
    }

    public static NatureException invalidResponse(Throwable cause) {
        return new NatureException("INVALID_EONET_RESPONSE", null, null, false, cause);
    }

    public static NatureException timeout(Throwable cause) {
        return new NatureException("NATURE_TIMEOUT", null, null, false, cause);
    }

    public static NatureException unavailable(Throwable cause) {
        return new NatureException("NATURE_UNAVAILABLE", null, null, true, cause);
    }

    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
    public boolean retryable() { return retryable; }
}
