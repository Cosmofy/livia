package xyz.arryan.livia.errors;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class NewsException extends RuntimeException {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");
    private static final Set<String> KNOWN_UPSTREAM_CODES = Set.of(
            "INVALID_QUERY",
            "ARTICLE_NOT_FOUND",
            "RATE_LIMITED",
            "PROVIDER_RATE_LIMITED",
            "PROVIDER_UNAVAILABLE",
            "INVALID_PROVIDER_RESPONSE",
            "INTERNAL_ERROR");
    private static final Map<String, String> PUBLIC_MESSAGES = Map.ofEntries(
            Map.entry("INVALID_QUERY", "The news request is invalid."),
            Map.entry("ARTICLE_NOT_FOUND", "The requested news article was not found."),
            Map.entry("RATE_LIMITED", "Too many news requests were made. Please try again shortly."),
            Map.entry("PROVIDER_RATE_LIMITED", "The news provider is temporarily busy. Please try again later."),
            Map.entry("PROVIDER_UNAVAILABLE", "The news provider is temporarily unavailable."),
            Map.entry("INVALID_PROVIDER_RESPONSE", "The news provider returned an invalid response."),
            Map.entry("INTERNAL_ERROR", "The News service encountered an unexpected error."),
            Map.entry("NEWS_INVALID_RESPONSE", "The News service returned an invalid response."),
            Map.entry("NEWS_TIMEOUT", "The News service did not respond in time."),
            Map.entry("NEWS_UNAVAILABLE", "The News service is temporarily unavailable."),
            Map.entry("NEWS_UPSTREAM_ERROR", "The News service could not complete the request."));

    private final String code;
    private final Integer httpStatus;
    private final boolean retryable;
    private final Long retryAfterSeconds;

    private NewsException(
            String code,
            Integer httpStatus,
            boolean retryable,
            Long retryAfterSeconds,
            Throwable cause) {
        super(PUBLIC_MESSAGES.getOrDefault(code, PUBLIC_MESSAGES.get("NEWS_UPSTREAM_ERROR")), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static NewsException validation() {
        return new NewsException("INVALID_QUERY", null, false, null, null);
    }

    public static NewsException upstream(
            String upstreamCode,
            String fallbackCode,
            int httpStatus,
            Long retryAfterSeconds) {
        String code = upstreamCode != null
                && SAFE_CODE.matcher(upstreamCode).matches()
                && KNOWN_UPSTREAM_CODES.contains(upstreamCode)
                ? upstreamCode
                : fallbackCode;
        boolean selectedTransientStatus = httpStatus == 502 || httpStatus == 503;
        boolean rateLimited = httpStatus == 429
                || "RATE_LIMITED".equals(code)
                || "PROVIDER_RATE_LIMITED".equals(code);
        return new NewsException(code, httpStatus, selectedTransientStatus && !rateLimited,
                retryAfterSeconds, null);
    }

    public static NewsException invalidResponse(Throwable cause) {
        return new NewsException("NEWS_INVALID_RESPONSE", null, false, null, cause);
    }

    public static NewsException timeout(Throwable cause) {
        return new NewsException("NEWS_TIMEOUT", null, false, null, cause);
    }

    public static NewsException unavailable(Throwable cause) {
        return new NewsException("NEWS_UNAVAILABLE", null, false, null, cause);
    }

    public String code() {
        return code;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
