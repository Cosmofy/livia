package xyz.arryan.livia;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsArchitectureTest {

    @Test
    void newsIntegrationDoesNotDuplicateServiceOwnedStorageOrProviderAccess() throws IOException {
        List<Path> integrationFiles = List.of(
                Path.of("src/main/java/xyz/arryan/livia/clients/NewsClient.java"),
                Path.of("src/main/java/xyz/arryan/livia/services/NewsService.java"),
                Path.of("src/main/java/xyz/arryan/livia/datafetchers/NewsDataFetcher.java"),
                Path.of("src/main/java/xyz/arryan/livia/config/NewsClientProperties.java"));

        for (Path path : integrationFiles) {
            assertThat(Files.readString(path).toLowerCase())
                    .as(path.toString())
                    .doesNotContain("redis", "spaceflightnewsapi", "spaceflight-news-api");
        }

        assertThat(Files.readString(Path.of("build.gradle")))
                .doesNotContain("spring-boot-starter-data-redis");
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .doesNotContain("news.redis");
        assertThat(Files.readString(Path.of("stellate.ts")))
                .contains("'Query.news'");
    }
}
