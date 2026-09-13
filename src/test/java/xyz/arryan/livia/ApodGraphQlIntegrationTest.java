package xyz.arryan.livia;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.arryan.livia.codegen.types.Picture;
import xyz.arryan.livia.codegen.types.PictureMatchType;
import xyz.arryan.livia.codegen.types.SearchPicture;
import xyz.arryan.livia.codegen.types.EarthObservatoryPicture;
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
    void noSelectorReturnsTodayAsOnePicture() {
        when(service.picture(null)).thenReturn(picture(LocalDate.of(2026, 9, 4)));

        ExecutionResult result = queryExecutor.execute("{ pictures { astronomy { date title relevanceScore } } }");

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("pictures={astronomy=[{date=2026-09-04, title=A title, relevanceScore=null}]}");
        verify(service).picture(null);
        verifyNoMoreInteractions(service);
    }

    @Test
    void dateSelectorReturnsOnePictureAndSimilarUsesItsDate() {
        LocalDate date = LocalDate.of(2019, 4, 11);
        when(service.picture(date)).thenReturn(picture(date));
        when(service.similar(date, 3)).thenReturn(List.of(searchPicture(LocalDate.of(2019, 4, 12), 0.8, null)));

        ExecutionResult result = queryExecutor.execute("""
                { pictures { astronomy(date: "2019-04-11") {
                  date title
                  similar(limit: 3) { date title relevanceScore }
                } } }
                """);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("pictures={astronomy=[{date=2019-04-11", "similar=[{date=2019-04-12, title=A title, relevanceScore=0.8}]");
        verify(service).picture(date);
        verify(service).similar(date, 3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void searchReturnsFlatPicturesWithSearchMetadataAndNestedSimilarity() {
        LocalDate date = LocalDate.of(2024, 1, 1);
        when(service.search("germany", 10)).thenReturn(List.of(
                discoveryPicture(date, 0.9, List.of(PictureMatchType.LEXICAL, PictureMatchType.SEMANTIC))));
        when(service.similar(date, 2)).thenReturn(List.of(searchPicture(LocalDate.of(2024, 1, 2), 0.7, null)));

        ExecutionResult result = queryExecutor.execute("""
                { pictures { astronomy(search: "germany") {
                  date title relevanceScore matchTypes
                  similar(limit: 2) { date relevanceScore }
                } } }
                """);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.toSpecification().toString())
                .contains("relevanceScore=0.9", "matchTypes=[LEXICAL, SEMANTIC]", "relevanceScore=0.7");
        verify(service).search("germany", 10);
        verify(service).similar(date, 2);
        verifyNoMoreInteractions(service);
    }

    @Test
    void rejectsDateAndSearchTogether() {
        ExecutionResult result = queryExecutor.execute("""
                { pictures { astronomy(date: "2019-04-11", search: "germany") { title } } }
                """);

        assertThat(result.getErrors()).singleElement().satisfies(error ->
                assertThat(error.getExtensions()).containsEntry("code", "INVALID_PICTURE_LOOKUP"));
        verifyNoMoreInteractions(service);
    }

    @Test
    void removesTheOldApodAndPictureFieldsFromThePublicSchema() {
        List<Map<String, Object>> fields = queryExecutor.executeAndExtractJsonPath(
                "{ __type(name: \"Query\") { fields { name } } }", "data.__type.fields");

        assertThat(fields).extracting(field -> field.get("name"))
                .contains("pictures")
                .doesNotContain("apod", "picture");

        List<Map<String, Object>> pictureFields = queryExecutor.executeAndExtractJsonPath(
                "{ __type(name: \"Picture\") { fields { name } } }", "data.__type.fields");
        assertThat(pictureFields).extracting(field -> field.get("name"))
                .contains("url")
                .doesNotContain("hdUrl", "fallbackUrl");
    }

    @Test
    void similarityFailureIsIsolatedToTheNestedField() {
        when(service.picture(null)).thenReturn(picture(LocalDate.of(2026, 9, 4)));
        when(service.similar(LocalDate.of(2026, 9, 4), 5))
                .thenThrow(ApodException.upstream("SIMILARITY_UNAVAILABLE", "APOD_UPSTREAM_ERROR", 503));

        ExecutionResult result = queryExecutor.execute("{ pictures { astronomy { title similar(limit: 5) { title } } } }");

        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getPath()).containsExactly("pictures", "astronomy", 0, "similar");
            assertThat(error.getExtensions()).containsEntry("code", "SIMILARITY_UNAVAILABLE");
        });
        Map<String, Object> data = result.getData();
        Map<String, Object> pictures = (Map<String, Object>) data.get("pictures");
        assertThat((List<?>) pictures.get("astronomy")).singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("title", "A title").containsEntry("similar", null);
    }

    @Test
    void earthObservatoryUsesAnOptionalDateAndItsOwnPictureType() {
        LocalDate date = LocalDate.of(2026, 9, 11);
        when(service.earthObservatory(date)).thenReturn(EarthObservatoryPicture.newBuilder()
                .date(date).title("Monterrey").explanation("An explanation").mediaType("image")
                .url("https://example.com/eo.jpg").articleUrl("https://example.com/article").build());
        ExecutionResult result = queryExecutor.execute("{ pictures { earthObservatory(date: \"2026-09-11\") { date title url articleUrl } } }");
        assertThat(result.getErrors()).isEmpty();
        verify(service).earthObservatory(date);
        verifyNoMoreInteractions(service);
    }

    @Test
    private static Picture picture(LocalDate date) {
        return Picture.newBuilder().date(date).title("A title").explanation("An explanation")
                .mediaType("image").url("https://example.com/apod.jpg").build();
    }

    private static Picture discoveryPicture(LocalDate date, double score, List<PictureMatchType> matchTypes) {
        Picture picture = picture(date);
        picture.setRelevanceScore(score);
        picture.setMatchTypes(matchTypes);
        return picture;
    }

    private static SearchPicture searchPicture(LocalDate date, double score, List<PictureMatchType> matchTypes) {
        return SearchPicture.newBuilder().date(date).title("A title").explanation("An explanation")
                .mediaType("image").url("https://example.com/apod.jpg").relevanceScore(score)
                .matchTypes(matchTypes).build();
    }
}
