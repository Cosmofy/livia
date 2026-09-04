package xyz.arryan.livia;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodSearchMode;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.services.ApodService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
})
class ApodGraphQlIntegrationTest {

    @Autowired
    private DgsQueryExecutor queryExecutor;

    @MockitoBean
    private ApodService service;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Test
    void resolvesNoDateAndHistoricalDateThroughOneTypedQuery() {
        when(service.get(null)).thenReturn(apod(LocalDate.of(2026, 9, 4)));
        when(service.get(LocalDate.of(2024, 1, 1))).thenReturn(apod(LocalDate.of(2024, 1, 1)));

        String today = queryExecutor.executeAndExtractJsonPath(
                "query TodayApod { apod { date title mediaType url hdUrl } }",
                "data.apod.date");
        String historical = queryExecutor.executeAndExtractJsonPath(
                "query HistoricalApod { apod(date: \"2024-01-01\") { date } }",
                "data.apod.date");

        assertThat(today).isEqualTo("2026-09-04");
        assertThat(historical).isEqualTo("2024-01-01");
        verify(service).get(null);
        verify(service).get(LocalDate.of(2024, 1, 1));
    }

    @Test
    void resolvesSearchWithItsSchemaDefaultLimit() {
        ApodSearchPayload payload = ApodSearchPayload.newBuilder()
                .query("spiral galaxy")
                .searchMode(ApodSearchMode.HYBRID)
                .results(List.of())
                .build();
        when(service.search("spiral galaxy", 10)).thenReturn(payload);

        String mode = queryExecutor.executeAndExtractJsonPath(
                "query SearchApods { searchApods(query: \"spiral galaxy\") { query searchMode results { relevanceScore } } }",
                "data.searchApods.searchMode");

        assertThat(mode).isEqualTo("HYBRID");
        verify(service).search("spiral galaxy", 10);
    }

    @Test
    void exposesSafeStructuredErrorExtensions() {
        when(service.get(LocalDate.of(1995, 6, 15)))
                .thenThrow(ApodException.upstream("DATE_TOO_EARLY", "APOD_UPSTREAM_ERROR", 400));

        ExecutionResult result = queryExecutor.execute(
                "query HistoricalApod { apod(date: \"1995-06-15\") { date } }");

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().getExtensions())
                .containsEntry("code", "DATE_TOO_EARLY")
                .containsEntry("service", "apod")
                .containsEntry("upstreamStatus", 400)
                .containsKey("requestId");
        assertThat(result.getErrors().getFirst().getMessage())
                .isEqualTo("NASA's Astronomy Picture of the Day archive begins on June 16, 1995.");
    }

    @Test
    void federationServiceSdlDeclaresVersionTwoAndTheApodKey() {
        String sdl = queryExecutor.executeAndExtractJsonPath(
                "{ _service { sdl } }",
                "data._service.sdl");

        assertThat(sdl)
                .contains("https://specs.apollo.dev/federation/v2.3")
                .contains("union _Entity = Apod")
                .contains("type Apod @key")
                .contains("fields : \"date\"");
    }

    @Test
    void resolvesTheApodFederationEntityByDate() {
        LocalDate date = LocalDate.of(2024, 1, 1);
        when(service.get(date)).thenReturn(apod(date));
        Map<String, Object> variables = Map.of(
                "representations",
                List.of(Map.of("__typename", "Apod", "date", "2024-01-01")));

        String title = queryExecutor.executeAndExtractJsonPath(
                "query ResolveApod($representations: [_Any!]!) {"
                        + " _entities(representations: $representations) {"
                        + " ... on Apod { date title } } }",
                "data._entities[0].title",
                variables);

        assertThat(title).isEqualTo("A title");
        verify(service).get(date);
    }

    private static Apod apod(LocalDate date) {
        return Apod.newBuilder()
                .date(date)
                .title("A title")
                .explanation("An explanation")
                .mediaType("image")
                .url("https://example.com/apod.jpg")
                .hdUrl(null)
                .build();
    }
}
