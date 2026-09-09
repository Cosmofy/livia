package xyz.arryan.livia.errors;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ApodException extends RuntimeException {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");
    private static final Set<String> KNOWN_UPSTREAM_CODES = Set.of(
            "DATE_TOO_EARLY",
            "DATE_IN_FUTURE",
            "NOT_FOUND",
            "INVALID_DATE_FORMAT",
            "NASA_UNAVAILABLE",
            "INVALID_NASA_RESPONSE",
            "NASA_RATE_LIMITED",
            "APOD_REQUEST_IN_PROGRESS",
            "INVALID_SEARCH_QUERY",
            "SEARCH_UNAVAILABLE",
            "INVALID_SIMILARITY_REQUEST",
            "SIMILARITY_UNAVAILABLE",
            "INTERNAL_ERROR");
    private static final Map<String, String> PUBLIC_MESSAGES = Map.ofEntries(
            Map.entry("DATE_TOO_EARLY", "NASA's Astronomy Picture of the Day archive begins on June 16, 1995."),
            Map.entry("DATE_IN_FUTURE", "NASA's Astronomy Picture of the Day is not available for future dates."),
            Map.entry("NOT_FOUND", "No Astronomy Picture of the Day was found for the requested date."),
            Map.entry("NASA_UNAVAILABLE", "NASA's Astronomy Picture of the Day service is temporarily unavailable."),
            Map.entry("INVALID_DATE_FORMAT", "Date must use the YYYY-MM-DD format."),
            Map.entry("NASA_RATE_LIMITED", "NASA's Astronomy Picture of the Day server is temporarily busy. Please try again later."),
            Map.entry("INVALID_NASA_RESPONSE", "NASA returned an invalid Astronomy Picture of the Day response."),
            Map.entry("INTERNAL_ERROR", "The APOD service encountered an unexpected error."),
            Map.entry("APOD_REQUEST_IN_PROGRESS", "The requested Astronomy Picture of the Day is currently being retrieved. Please try again shortly."),
            Map.entry("INVALID_SEARCH_QUERY", "Search must contain between 1 and 200 searchable characters."),
            Map.entry("SEARCH_UNAVAILABLE", "Astronomy Picture of the Day search is temporarily unavailable."),
            Map.entry("INVALID_SIMILARITY_REQUEST", "Similarity requires a valid APOD date and a limit between 1 and 50."),
            Map.entry("SIMILARITY_UNAVAILABLE", "Astronomy Picture of the Day similarity is temporarily unavailable."),
            Map.entry("APOD_INVALID_RESPONSE", "The APOD service returned an invalid response."),
            Map.entry("APOD_TIMEOUT", "The APOD service did not respond in time."),
            Map.entry("APOD_UNAVAILABLE", "The APOD service is temporarily unavailable."),
            Map.entry("APOD_UPSTREAM_ERROR", "The APOD service could not complete the request."));

    private final String code;
    private final Integer httpStatus;
    private final boolean retryable;

    private ApodException(String code, Integer httpStatus, boolean retryable, Throwable cause) {
        super(PUBLIC_MESSAGES.getOrDefault(code, PUBLIC_MESSAGES.get("APOD_UPSTREAM_ERROR")), cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public static ApodException validation(String code) {
        return new ApodException(code, null, false, null);
    }

    public static ApodException upstream(String upstreamCode, String fallbackCode, int httpStatus) {
        String code = upstreamCode != null
                && SAFE_CODE.matcher(upstreamCode).matches()
                && KNOWN_UPSTREAM_CODES.contains(upstreamCode)
                ? upstreamCode
                : fallbackCode;
        boolean selectedTransientStatus = httpStatus == 502 || httpStatus == 503;
        boolean retryable = selectedTransientStatus && !"NASA_RATE_LIMITED".equals(code);
        return new ApodException(code, httpStatus, retryable, null);
    }

    public static ApodException invalidResponse(Throwable cause) {
        return new ApodException("APOD_INVALID_RESPONSE", null, false, cause);
    }

    public static ApodException timeout(Throwable cause) {
        return new ApodException("APOD_TIMEOUT", null, false, cause);
    }

    public static ApodException unavailable(Throwable cause) {
        return new ApodException("APOD_UNAVAILABLE", null, false, cause);
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
}
