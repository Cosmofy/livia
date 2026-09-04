package xyz.arryan.livia.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "articles.client")
public record ArticlesClientProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration requestTimeout,
        @Min(1) @Max(3) int maxAttempts,
        @NotNull Duration retryBackoff) {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    @AssertTrue(message = "articles.client.base-url must be an absolute HTTP(S) URL without credentials, a query, or a fragment")
    public boolean isBaseUrlValid() {
        return baseUrl != null
                && ALLOWED_SCHEMES.contains(baseUrl.getScheme())
                && baseUrl.getHost() != null
                && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null
                && baseUrl.getFragment() == null;
    }

    @AssertTrue(message = "timeouts must be positive and at most 60 seconds; Articles needs at least 15 seconds overall")
    public boolean isTimeoutsValid() {
        return isPositiveAtMost(connectTimeout, MAX_TIMEOUT)
                && isPositiveAtMost(requestTimeout, MAX_TIMEOUT)
                && requestTimeout.compareTo(Duration.ofSeconds(15)) >= 0;
    }

    @AssertTrue(message = "articles.client.retry-backoff must be positive and no greater than 5 seconds")
    public boolean isRetryBackoffValid() {
        return isPositiveAtMost(retryBackoff, MAX_BACKOFF);
    }

    private static boolean isPositiveAtMost(Duration value, Duration maximum) {
        return value != null && !value.isZero() && !value.isNegative() && value.compareTo(maximum) <= 0;
    }
}
