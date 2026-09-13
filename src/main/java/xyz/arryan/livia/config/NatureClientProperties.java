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
@ConfigurationProperties(prefix = "nature.client")
public record NatureClientProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration requestTimeout,
        @Min(1) @Max(3) int maxAttempts,
        @NotNull Duration retryBackoff) {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @AssertTrue(message = "nature.client.base-url must be an absolute HTTP(S) URL without credentials, a query, or a fragment")
    public boolean isBaseUrlValid() {
        return baseUrl != null && ALLOWED_SCHEMES.contains(baseUrl.getScheme())
                && baseUrl.getHost() != null && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null && baseUrl.getFragment() == null;
    }

    @AssertTrue(message = "Nature timeouts and retry backoff must be positive and at most 60 seconds")
    public boolean areDurationsValid() {
        return validDuration(connectTimeout) && validDuration(requestTimeout) && validDuration(retryBackoff);
    }

    private static boolean validDuration(Duration value) {
        return value != null && !value.isNegative() && !value.isZero() && value.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
