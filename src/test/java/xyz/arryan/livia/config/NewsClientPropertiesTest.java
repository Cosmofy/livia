package xyz.arryan.livia.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NewsClientPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBoundedClientConfiguration() {
        NewsClientProperties properties = properties(
                URI.create("https://news.internal"), Duration.ofSeconds(3), Duration.ofSeconds(15), 2);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsCredentialsInUrlAndUnsafeTimeoutOrRetryValues() {
        NewsClientProperties properties = properties(
                URI.create("https://user:secret@news.internal?token=secret"),
                Duration.ZERO,
                Duration.ofSeconds(14),
                10);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("baseUrlValid", "timeoutsValid", "maxAttempts");
    }

    private static NewsClientProperties properties(
            URI baseUrl,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxAttempts) {
        return new NewsClientProperties(
                baseUrl, connectTimeout, requestTimeout, maxAttempts, Duration.ofMillis(100));
    }
}
