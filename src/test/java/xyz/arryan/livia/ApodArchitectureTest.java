package xyz.arryan.livia;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApodArchitectureTest {

    @Test
    void apodIntegrationDoesNotDuplicateServiceOwnedStorageOrEmbeddingDependencies() throws IOException {
        List<Path> integrationFiles = List.of(
                Path.of("src/main/java/xyz/arryan/livia/clients/ApodClient.java"),
                Path.of("src/main/java/xyz/arryan/livia/services/ApodService.java"),
                Path.of("src/main/java/xyz/arryan/livia/datafetchers/ApodDataFetcher.java"),
                Path.of("src/main/java/xyz/arryan/livia/config/ApodClientProperties.java"));

        for (Path path : integrationFiles) {
            assertThat(Files.readString(path).toLowerCase())
                    .as(path.toString())
                    .doesNotContain("redis", "turso", "embedding", "openai");
        }

        assertThat(Files.readString(Path.of("build.gradle")))
                .doesNotContain("spring-boot-starter-data-redis");
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .doesNotContain("apod.redis", "apod.turso");
    }
}
