package xyz.arryan.livia.errors;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ArticlesException extends RuntimeException {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");
    private static final Set<String> KNOWN_UPSTREAM_CODES = Set.of(
            "INVALID_QUERY",
            "ARTICLE_NOT_FOUND",
            "RATE_LIMITED",
            "INVALID_DATASET",
            "INTERNAL_ERROR");
    private static final Map<String, String> PUBLIC_MESSAGES = Map.ofEntries(
            Map.entry("INVALID_QUERY", "The article request is invalid."),
            Map.entry("ARTICLE_NOT_FOUND", "The requested article was not found."),
            Map.entry("RATE_LIMITED", "Too many article requests were made. Please try again shortly."),
            Map.entry("INVALID_DATASET", "The Articles service dataset is temporarily unavailable."),
            Map.entry("INTERNAL_ERROR", "The Articles service encountered an unexpected error."),
            Map.entry("ARTICLES_INVALID_RESPONSE", "The Articles service returned an invalid response."),
            Map.entry("ARTICLES_TIMEOUT", "The Articles service did not respond in time."),
            Map.entry("ARTICLES_UNAVAILABLE", "The Articles service is temporarily unavailable."),
            Map.entry("ARTICLES_UPSTREAM_ERROR", "The Articles service could not complete the request."));

    private final String code;
    private final Integer httpStatus;
    private final boolean retryable;
    private final Long retryAfterSeconds;

    private ArticlesException(
            String code,
            Integer httpStatus,
            boolean retryable,
            Long retryAfterSeconds,
            Throwable cause) {
        super(PUBLIC_MESSAGES.getOrDefault(code, PUBLIC_MESSAGES.get("ARTICLES_UPSTREAM_ERROR")), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ArticlesException validation() {
        return new ArticlesException("INVALID_QUERY", null, false, null, null);
    }

    public static ArticlesException upstream(
            String upstreamCode,
            String fallbackCode,
            int httpStatus,
            Long retryAfterSeconds) {
        String code = upstreamCode != null
                && SAFE_CODE.matcher(upstreamCode).matches()
                && KNOWN_UPSTREAM_CODES.contains(upstreamCode)
                ? upstreamCode
                : fallbackCode;
        boolean rateLimited = httpStatus == 429 || "RATE_LIMITED".equals(code);
        boolean retryable = (httpStatus == 502 || httpStatus == 503) && !rateLimited;
        return new ArticlesException(code, httpStatus, retryable, retryAfterSeconds, null);
    }

    public static ArticlesException invalidResponse(Throwable cause) {
        return new ArticlesException("ARTICLES_INVALID_RESPONSE", null, false, null, cause);
    }

    public static ArticlesException timeout(Throwable cause) {
        return new ArticlesException("ARTICLES_TIMEOUT", null, false, null, cause);
    }

    public static ArticlesException unavailable(Throwable cause) {
        return new ArticlesException("ARTICLES_UNAVAILABLE", null, false, null, cause);
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
