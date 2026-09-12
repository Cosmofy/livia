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
import xyz.arryan.livia.codegen.types.ApodSearchResult;
import xyz.arryan.livia.codegen.types.ApodMatchType;
import xyz.arryan.livia.codegen.types.ApodSimilarityPayload;
import xyz.arryan.livia.codegen.types.ApodSimilarityResult;
import xyz.arryan.livia.mappers.ApodMapper;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.services.ApodService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
                .results(List.of(ApodSearchResult.newBuilder()
                        .date(LocalDate.of(2024, 1, 1))
                        .title("A title").explanation("An explanation")
                        .mediaType("image").url("https://example.com/apod.jpg")
                        .hdUrl(null).credit("An author").copyright(null)
                        .fallbackUrl("https://apod.nasa.gov/search-fallback.jpg")
                        .relevanceScore(1.0).matchTypes(List.of(ApodMatchType.SEMANTIC))
                        .build()))
                .build();
        when(service.search("spiral galaxy", 10)).thenReturn(payload);

        ExecutionResult result = queryExecutor.execute("""
                query SearchApods {
                  apod {
                    search(query: "spiral galaxy") {
                      query searchMode
                      results { date title explanation mediaType url hdUrl fallbackUrl credit copyright relevanceScore matchTypes }
                    }
                  }
                }
                """);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("searchMode=HYBRID", "date=2024-01-01", "credit=An author", "relevanceScore=1.0",
                        "fallbackUrl=https://apod.nasa.gov/search-fallback.jpg");
        verify(service).search("spiral galaxy", 10);
        verifyNoMoreInteractions(service);
    }

    @Test
    void resolvesTodayAndAliasedDatesWithAllPictureFieldsWithoutFetchingTheRootDate() {
        var mapper = new ApodMapper();
        when(service.getPicture(null)).thenReturn(mapper.toPicture(apod(LocalDate.of(2026, 9, 4))));
        when(service.getPicture(LocalDate.of(2024, 1, 1)))
                .thenReturn(mapper.toPicture(apod(LocalDate.of(2024, 1, 1))));
        when(service.getPicture(LocalDate.of(2024, 1, 2)))
                .thenReturn(mapper.toPicture(apod(LocalDate.of(2024, 1, 2))));

        ExecutionResult result = queryExecutor.execute("""
                query GroupedApod {
                  apod(date: "2000-01-01") {
                    today { ...PictureFields }
                    first: byDate(date: "2024-01-01") { ...PictureFields }
                    second: byDate(date: "2024-01-02") { ...PictureFields }
                  }
                }
                fragment PictureFields on ApodPicture {
                  date title explanation mediaType url hdUrl fallbackUrl credit copyright
                }
                """);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("today={date=2026-09-04", "first={date=2024-01-01", "second={date=2024-01-02",
                        "fallbackUrl=null");
        verify(service).getPicture(null);
        verify(service).getPicture(LocalDate.of(2024, 1, 1));
        verify(service).getPicture(LocalDate.of(2024, 1, 2));
        verifyNoMoreInteractions(service);
    }

    @Test
    void preservesLegacyFragmentsAliasesAndMixedOldAndNewSelections() {
        LocalDate date = LocalDate.of(2024, 1, 1);
        when(service.get(date)).thenReturn(apod(date));
        when(service.getPicture(null)).thenReturn(new ApodMapper().toPicture(apod(LocalDate.of(2026, 9, 4))));

        ExecutionResult result = queryExecutor.execute("""
                query MixedApod {
                  apod(date: "2024-01-01") {
                    ...LegacyPicture
                    today { date }
                  }
                }
                fragment LegacyPicture on Apod {
                  oldDate: date title explanation mediaType url hdUrl credit copyright
                }
                """);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("oldDate=2024-01-01", "today={date=2026-09-04}");
        verify(service).get(date);
        verify(service).getPicture(null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void skippedLegacyFieldsDoNotTriggerAnUpstreamLookup() {
        when(service.search("galaxy", 10)).thenReturn(ApodSearchPayload.newBuilder()
                .query("galaxy").searchMode(ApodSearchMode.LEXICAL).results(List.of()).build());

        ExecutionResult result = queryExecutor.execute("""
                query SkippedLegacy($legacy: Boolean!) {
                  apod {
                    ...LegacyFields @include(if: $legacy)
                    url @skip(if: true)
                    search(query: "galaxy") { results { title } }
                  }
                }
                fragment LegacyFields on Apod { title }
                """, Map.of("legacy", false));

        assertThat(result.getErrors()).isEmpty();
        verify(service).search("galaxy", 10);
        verifyNoMoreInteractions(service);
    }

    @Test
    void deprecatesOnlyLegacyPictureFieldsAndRemovesTheUnusedRootSearch() {
        List<Map<String, Object>> fields = queryExecutor.executeAndExtractJsonPath(
                "{ __type(name: \"Apod\") { fields(includeDeprecated: true) { name isDeprecated } } }",
                "data.__type.fields");
        assertThat(fields).filteredOn(field -> Boolean.TRUE.equals(field.get("isDeprecated")))
                .extracting(field -> field.get("name"))
                .containsExactlyInAnyOrder("date", "title", "explanation", "mediaType", "url", "hdUrl", "fallbackUrl", "credit", "copyright");
        assertThat(fields).filteredOn(field -> Boolean.FALSE.equals(field.get("isDeprecated")))
                .extracting(field -> field.get("name"))
                .contains("today", "byDate", "search");
        ExecutionResult oldSearch = queryExecutor.execute("{ searchApods(query: \"galaxy\") { query } }");
        assertThat(oldSearch.getErrors()).isNotEmpty();
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
    void resolvesFlatSimilarityWithTheDefaultLimitAndNoLookup() {
        LocalDate date = LocalDate.of(2024, 2, 29);
        when(service.similar(date, 10)).thenReturn(ApodSimilarityPayload.newBuilder()
                .date(date).results(List.of(ApodSimilarityResult.newBuilder()
                        .date(LocalDate.of(2024, 1, 1)).title("A related picture")
                        .explanation("An explanation").mediaType("image").url("")
                        .hdUrl(null).credit(null).copyright(null).relevanceScore(0.8)
                        .fallbackUrl("https://apod.nasa.gov/similarity-fallback.jpg").build())).build());
        ExecutionResult result = queryExecutor.execute("""
                { apod { similar(date: "2024-02-29") {
                  date results { date title explanation mediaType url hdUrl fallbackUrl credit copyright relevanceScore }
                } } }
                """);
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString()).contains("title=A related picture", "relevanceScore=0.8",
                "fallbackUrl=https://apod.nasa.gov/similarity-fallback.jpg");
        verify(service).similar(date, 10);
        verifyNoMoreInteractions(service);
    }

    @Test
    void similarityFailurePreservesOtherRequestedData() {
        LocalDate date = LocalDate.of(2024, 2, 29);
        when(service.similar(date, 5)).thenThrow(
                ApodException.upstream("SIMILARITY_UNAVAILABLE", "APOD_UPSTREAM_ERROR", 503));
        when(service.getPicture(null)).thenReturn(new ApodMapper().toPicture(apod(date)));
        ExecutionResult result = queryExecutor.execute("""
                { apod { today { title } similar(date: "2024-02-29", limit: 5) { results { title } } } }
                """);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().getPath()).containsExactly("apod", "similar");
        assertThat(result.getErrors().getFirst().getExtensions()).containsEntry("code", "SIMILARITY_UNAVAILABLE");
        Map<String, Object> data = result.getData();
        assertThat((Map<String, Object>) data.get("apod"))
                .containsEntry("today", Map.of("title", "A title")).containsEntry("similar", null);
    }

    @Test
    void fallbackOnlyLegacySelectionFetchesThePictureAndMapsTheNewLookupShape() {
        LocalDate date = LocalDate.of(2019, 4, 11);
        var picture = apod(date);
        picture.setFallbackUrl("https://apod.nasa.gov/source.jpg");
        when(service.get(date)).thenReturn(picture);
        when(service.getPicture(date)).thenReturn(new ApodMapper().toPicture(picture));
        ExecutionResult result = queryExecutor.execute("""
                { apod(date: "2019-04-11") {
                  fallbackUrl
                  byDate(date: "2019-04-11") { fallbackUrl }
                } }
                """);
        assertThat(result.getErrors()).isEmpty();
        Map<String, Object> data = result.getData();
        assertThat((Map<String, Object>) data.get("apod"))
                .containsEntry("fallbackUrl", "https://apod.nasa.gov/source.jpg")
                .containsEntry("byDate", Map.of("fallbackUrl", "https://apod.nasa.gov/source.jpg"));
        verify(service).get(date);
        verify(service).getPicture(date);
        verifyNoMoreInteractions(service);
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
