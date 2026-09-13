package xyz.arryan.livia;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NatureArchitectureTest {
    @Test
    void natureIntegrationDoesNotRestoreDirectEonetOrRedisAccess() throws IOException {
        List<Path> integrationFiles = List.of(
                Path.of("src/main/java/xyz/arryan/livia/clients/NatureClient.java"),
                Path.of("src/main/java/xyz/arryan/livia/services/NatureService.java"),
                Path.of("src/main/java/xyz/arryan/livia/datafetchers/EventsDataFetcher.java"));
        for (Path path : integrationFiles) {
            String source = Files.readString(path).toLowerCase();
            assertThat(source).as(path.toString()).doesNotContain("eonet.gsfc.nasa.gov", "redis");
        }
    }
}
