package xyz.arryan.livia.mappers;

import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
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
    void mapsRankModeEnumsAndCanonicalEntityWrappers() {
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
                List.of("lexical", "semantic"));
        ApodSearchResponse response = new ApodSearchResponse(
                "spiral galaxy", "hybrid", List.of(searchResult));

        ApodSearchPayload result = mapper.toGraphQl(response);

        assertThat(result.getQuery()).isEqualTo("spiral galaxy");
        assertThat(result.getSearchMode()).isEqualTo(ApodSearchMode.HYBRID);
        assertThat(result.getResults()).hasSize(1);
        assertThat(result.getResults().getFirst().getApod().getDate())
                .isEqualTo(LocalDate.of(1997, 4, 19));
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
                LocalDate.of(2026, 9, 4), "A title", null, "image", "", null, null, null);
        assertCode(() -> mapper.toGraphQl(malformed));

        ApodSearchResultResponse invalidScore = new ApodSearchResultResponse(
                LocalDate.of(2026, 9, 4), "Title", "Explanation", "image", "", null, null, null,
                1.1, List.of("lexical"));
        assertCode(() -> mapper.toGraphQl(new ApodSearchResponse("query", "hybrid", List.of(invalidScore))));

        ApodSearchResultResponse nonFiniteScore = new ApodSearchResultResponse(
                LocalDate.of(2026, 9, 4), "Title", "Explanation", "image", "", null, null, null,
                Double.NaN, List.of("semantic"));
        assertCode(() -> mapper.toGraphQl(new ApodSearchResponse("query", "semantic", List.of(nonFiniteScore))));
    }

    private void assertCode(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ApodException.class,
                        exception -> assertThat(exception.code()).isEqualTo("APOD_INVALID_RESPONSE"));
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
                null);
    }
}
