package xyz.arryan.livia.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApodClientPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBoundedClientConfiguration() {
        ApodClientProperties properties = properties(
                URI.create("https://apod.internal"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(35),
                Duration.ofSeconds(15),
                2);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsCredentialsInUrlAndUnsafeTimeoutOrRetryValues() {
        ApodClientProperties properties = properties(
                URI.create("https://user:secret@apod.internal?token=secret"),
                Duration.ZERO,
                Duration.ofMinutes(2),
                Duration.ofSeconds(14),
                10);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("baseUrlValid", "timeoutsValid", "maxAttempts");
    }

    private static ApodClientProperties properties(
            URI baseUrl,
            Duration connectTimeout,
            Duration apodRequestTimeout,
            Duration searchRequestTimeout,
            int maxAttempts) {
        return new ApodClientProperties(
                baseUrl,
                connectTimeout,
                apodRequestTimeout,
                searchRequestTimeout,
                maxAttempts,
                Duration.ofMillis(100));
    }
}
