package xyz.arryan.livia.mappers;

import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResultResponse;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodMatchType;
import xyz.arryan.livia.codegen.types.ApodSearchMode;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.errors.ApodException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApodMapperTest {

    private final ApodMapper mapper = new ApodMapper();

    @Test
    void mapsEveryFieldAndNullableField() {
        Apod result = mapper.toGraphQl(response("image", "https://example.com/apod.jpg"));

        assertThat(result.getDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(result.getTitle()).isEqualTo("A title");
        assertThat(result.getExplanation()).isEqualTo("An explanation");
        assertThat(result.getMediaType()).isEqualTo("image");
        assertThat(result.getUrl()).isEqualTo("https://example.com/apod.jpg");
        assertThat(result.getHdUrl()).isEqualTo("https://example.com/apod-hd.jpg");
        assertThat(result.getCredit()).isNull();
        assertThat(result.getCopyright()).isNull();
    }

    @Test
    void preservesVideoMediaAndAllowsHistoricalEmptyUrls() {
        Apod result = mapper.toGraphQl(response("video", ""));

        assertThat(result.getMediaType()).isEqualTo("video");
        assertThat(result.getUrl()).isEmpty();
    }

    @Test
    void defaultsAnOmittedTransportUrlToTheGraphQlNonNullValue() {
        Apod result = mapper.toGraphQl(response("image", null));

        assertThat(result.getUrl()).isEmpty();
    }

    @Test
    void mapsRankModeEnumsAndFlatPictureFields() {
        ApodSearchResultResponse searchResult = new ApodSearchResultResponse(
                LocalDate.of(1997, 4, 19),
                "Spiral Galaxy M83",
                "Explanation",
                "image",
                "https://example.com/m83.jpg",
                null,
                null,
                null,
                1.0,
                List.of("lexical", "semantic"), null);
        ApodSearchResponse response = new ApodSearchResponse(
                "spiral galaxy", "hybrid", List.of(searchResult));

        ApodSearchPayload result = mapper.toGraphQl(response);

        assertThat(result.getQuery()).isEqualTo("spiral galaxy");
        assertThat(result.getSearchMode()).isEqualTo(ApodSearchMode.HYBRID);
        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().getFirst().getDate())
                .isEqualTo(LocalDate.of(1997, 4, 19));
        assertThat(result.getResults().getFirst().getTitle()).isEqualTo("Spiral Galaxy M83");
        assertThat(result.getResults().getFirst().getExplanation()).isEqualTo("Explanation");
        assertThat(result.getResults().getFirst().getMediaType()).isEqualTo("image");
        assertThat(result.getResults().getFirst().getUrl()).isEqualTo("https://example.com/m83.jpg");
        assertThat(result.getResults().getFirst().getHdUrl()).isNull();
        assertThat(result.getResults().getFirst().getCredit()).isNull();
        assertThat(result.getResults().getFirst().getCopyright()).isNull();
        assertThat(result.getResults().getFirst().getRelevanceScore()).isEqualTo(1.0);
        assertThat(result.getResults().getFirst().getMatchTypes())
                .containsExactly(ApodMatchType.LEXICAL, ApodMatchType.SEMANTIC);
    }

    @Test
    void acceptsEmptySearchResultsAndDegradedModes() {
        assertThat(mapper.toGraphQl(new ApodSearchResponse("nebula", "lexical", List.of())).getSearchMode())
                .isEqualTo(ApodSearchMode.LEXICAL);
        assertThat(mapper.toGraphQl(new ApodSearchResponse("nebula", "semantic", List.of())).getSearchMode())
                .isEqualTo(ApodSearchMode.SEMANTIC);
    }

    @Test
    void rejectsMalformedApodAndOutOfRangeSearchScores() {
        ApodResponse malformed = new ApodResponse(
                LocalDate.of(2026, 9, 4), "A title", null, "image", "", null, null, null, null);
        assertCode(() -> mapper.toGraphQl(malformed));

        ApodSearchResultResponse invalidScore = new ApodSearchResultResponse(
                LocalDate.of(2026, 9, 4), "Title", "Explanation", "image", "", null, null, null,
                1.1, List.of("lexical"), null);
        assertCode(() -> mapper.toGraphQl(new ApodSearchResponse("query", "hybrid", List.of(invalidScore))));

        ApodSearchResultResponse nonFiniteScore = new ApodSearchResultResponse(
                LocalDate.of(2026, 9, 4), "Title", "Explanation", "image", "", null, null, null,
                Double.NaN, List.of("semantic"), null);
        assertCode(() -> mapper.toGraphQl(new ApodSearchResponse("query", "semantic", List.of(nonFiniteScore))));
    }

    private void assertCode(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ApodException.class,
                        exception -> assertThat(exception.code()).isEqualTo("APOD_INVALID_RESPONSE"));
    }

    @Test
    void mapsSimilarityAndRejectsInvalidRankingsAndSourceInclusion() {
        LocalDate source = LocalDate.of(2024, 2, 29);
        var first = similarityResult(LocalDate.of(2024, 1, 1), 0.9);
        var second = similarityResult(LocalDate.of(2024, 1, 2), 0.7);
        var mapped = mapper.toGraphQl(new ApodSimilarityResponse(source, List.of(first, second)));
        assertThat(mapped.getDate()).isEqualTo(source);
        assertThat(mapped.getResults()).hasSize(2);
        assertThat(mapped.getResults().getFirst().getCredit()).isEqualTo("Author");
        assertThat(mapped.getResults().getFirst().getUrl()).isEmpty();
        assertThat(mapped.getResults().getFirst().getRelevanceScore()).isEqualTo(0.9);
        assertCode(() -> mapper.toGraphQl(new ApodSimilarityResponse(source, List.of(second, first))));
        assertCode(() -> mapper.toGraphQl(new ApodSimilarityResponse(source, List.of(first, first))));
        assertCode(() -> mapper.toGraphQl(new ApodSimilarityResponse(source, List.of(similarityResult(source, 1.0)))));
        for (double score : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1}) {
            assertCode(() -> mapper.toGraphQl(new ApodSimilarityResponse(source,
                    List.of(similarityResult(first.date(), score)))));
        }
    }

    private static ApodSimilarityResultResponse similarityResult(LocalDate date, double score) {
        return new ApodSimilarityResultResponse(date, "Title", "Explanation", "image", null, null,
                "Author", null, score, null);
    }

    private static ApodResponse response(String mediaType, String url) {
        return new ApodResponse(
                LocalDate.of(2026, 9, 4),
                "A title",
                "An explanation",
                mediaType,
                url,
                "https://example.com/apod-hd.jpg",
                null,
                null,
                null);
    }
}
