package xyz.arryan.livia.mappers;

import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResultResponse;
import xyz.arryan.livia.codegen.types.PictureMatchType;
import xyz.arryan.livia.errors.ApodException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApodMapperTest {

    private final ApodMapper mapper = new ApodMapper();

    @Test
    void mapsAnExactPictureWithoutDiscoveryMetadata() {
        var picture = mapper.toPicture(exact(LocalDate.of(2026, 9, 4)));

        assertThat(picture.getDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(picture.getUrl()).isEqualTo("https://example.com/apod.jpg");
        assertThat(picture.getRelevanceScore()).isNull();
        assertThat(picture.getMatchTypes()).isNull();
    }

    @Test
    void mapsSearchMetadataOntoTheSamePictureType() {
        var result = new ApodSearchResultResponse(LocalDate.of(1997, 4, 19), "M83", "Explanation", "image",
                "https://example.com/m83.jpg", null, null, 1.0, List.of("lexical", "semantic"), null);

        var pictures = mapper.toPictures(new ApodSearchResponse("spiral galaxy", "hybrid", List.of(result)));

        assertThat(pictures).singleElement().satisfies(picture -> {
            assertThat(picture.getRelevanceScore()).isEqualTo(1.0);
            assertThat(picture.getMatchTypes()).containsExactly(PictureMatchType.LEXICAL, PictureMatchType.SEMANTIC);
        });
    }

    @Test
    void mapsSimilarityMetadataOntoSearchPicture() {
        LocalDate source = LocalDate.of(2024, 2, 29);
        var result = new ApodSimilarityResultResponse(LocalDate.of(2024, 1, 1), "Related", "Explanation", "image",
                "", null, null, 0.8, null);

        var pictures = mapper.toSearchPictures(new ApodSimilarityResponse(source, List.of(result)), source);

        assertThat(pictures).singleElement().satisfies(picture -> {
            assertThat(picture.getRelevanceScore()).isEqualTo(0.8);
            assertThat(picture.getMatchTypes()).isNull();
        });
    }

    @Test
    void rejectsMalformedResponsesAndInvalidScores() {
        assertInvalid(() -> mapper.toPicture(new ApodResponse(LocalDate.now(), "Title", null, "image", "", null, null, null)));
        var invalid = new ApodSearchResultResponse(LocalDate.now(), "Title", "Explanation", "image", "", null,
                null, 1.1, List.of("lexical"), null);
        assertInvalid(() -> mapper.toPictures(new ApodSearchResponse("query", "hybrid", List.of(invalid))));
    }

    private static void assertInvalid(Runnable invocation) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(ApodException.class,
                exception -> assertThat(exception.code()).isEqualTo("APOD_INVALID_RESPONSE"));
    }

    private static ApodResponse exact(LocalDate date) {
        return new ApodResponse(date, "A title", "An explanation", "image", "https://example.com/apod.jpg",
                null, null, null);
    }
}
