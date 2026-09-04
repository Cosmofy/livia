package xyz.arryan.livia;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticlesArchitectureTest {

    @Test
    void articlesIntegrationDoesNotDuplicateServiceOwnedStorageOrReadTheDataset() throws IOException {
        List<Path> integrationFiles = List.of(
                Path.of("src/main/java/xyz/arryan/livia/clients/ArticlesClient.java"),
                Path.of("src/main/java/xyz/arryan/livia/services/ArticlesService.java"),
                Path.of("src/main/java/xyz/arryan/livia/datafetchers/ArticlesDataFetcher.java"),
                Path.of("src/main/java/xyz/arryan/livia/config/ArticlesClientProperties.java"));

        for (Path path : integrationFiles) {
            assertThat(Files.readString(path).toLowerCase())
                    .as(path.toString())
                    .doesNotContain("redis", "mongotemplate", "articles.json", "mongo");
        }

        assertThat(Files.readString(Path.of("build.gradle")))
                .doesNotContain("spring-boot-starter-data-redis");
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .contains("dgs.graphql.federation.enabled=false")
                .doesNotContain("articles.redis");
        assertThat(Files.readString(Path.of("src/main/resources/schema/schema.graphqls")))
                .doesNotContain("@key", "@link", "_entities");
    }
}
