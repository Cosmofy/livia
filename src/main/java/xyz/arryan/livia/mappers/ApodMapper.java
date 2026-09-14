package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResultResponse;
import xyz.arryan.livia.codegen.types.Picture;
import xyz.arryan.livia.codegen.types.PictureMatchType;
import xyz.arryan.livia.codegen.types.SearchPicture;
import xyz.arryan.livia.clients.dto.EarthObservatoryResponse;
import xyz.arryan.livia.clients.dto.EarthObservatorySearchResponse;
import xyz.arryan.livia.clients.dto.EarthObservatorySearchResultResponse;
import xyz.arryan.livia.clients.dto.EarthObservatorySimilarityResponse;
import xyz.arryan.livia.clients.dto.EarthObservatorySimilarityResultResponse;
import xyz.arryan.livia.codegen.types.EarthObservatoryPicture;
import xyz.arryan.livia.errors.ApodException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import xyz.arryan.livia.codegen.types.EarthObservatorySearchPayload;
import xyz.arryan.livia.codegen.types.EarthObservatorySimilarityPayload;
import xyz.arryan.livia.codegen.types.EarthObservatorySearchResult;
import xyz.arryan.livia.codegen.types.EarthObservatorySimilarityResult;

@Component
public class ApodMapper {

    public EarthObservatoryPicture toEarthObservatoryPicture(EarthObservatoryResponse response) {
        if (response == null || response.date() == null || isBlank(response.title()) || isBlank(response.explanation())
                || isBlank(response.mediaType()) || isBlank(response.url()) || isBlank(response.articleUrl())) {
            throw ApodException.invalidResponse(null);
        }
        return EarthObservatoryPicture.newBuilder().date(response.date()).title(response.title())
                .explanation(response.explanation()).mediaType(response.mediaType()).url(response.url())
                .credit(response.credit()).copyright(response.copyright()).imageDate(response.imageDate())
                .locationName(response.locationName()).latitude(response.latitude()).longitude(response.longitude())
                .articleUrl(response.articleUrl()).build();
    }

    public EarthObservatorySearchPayload toEarthObservatorySearch(EarthObservatorySearchResponse response, int limit) {
        if (response == null || isBlank(response.query()) || isBlank(response.searchMode()) || response.results() == null || response.results().size() > limit)
            throw ApodException.invalidResponse(null);
        return EarthObservatorySearchPayload.newBuilder().query(response.query()).searchMode(response.searchMode())
                .results(response.results().stream().map(this::toEarthObservatorySearchResult).toList()).build();
    }

    public EarthObservatorySimilarityPayload toEarthObservatorySimilarity(EarthObservatorySimilarityResponse response, LocalDate sourceDate, int limit) {
        if (response == null || !sourceDate.equals(response.date()) || response.results() == null || response.results().size() > limit)
            throw ApodException.invalidResponse(null);
        return EarthObservatorySimilarityPayload.newBuilder().date(response.date())
                .results(response.results().stream().map(this::toEarthObservatorySimilarityResult).toList()).build();
    }

    private EarthObservatorySearchResult toEarthObservatorySearchResult(EarthObservatorySearchResultResponse r) {
        if (r == null || r.date() == null || isBlank(r.title()) || isBlank(r.explanation()) || isBlank(r.mediaType()) || isBlank(r.url()) || isBlank(r.articleUrl())) throw ApodException.invalidResponse(null);
        return EarthObservatorySearchResult.newBuilder().date(r.date()).title(r.title()).explanation(r.explanation()).mediaType(r.mediaType()).url(r.url())
                .credit(r.credit()).copyright(r.copyright()).imageDate(r.imageDate()).locationName(r.locationName()).latitude(r.latitude()).longitude(r.longitude()).articleUrl(r.articleUrl()).relevanceScore(r.relevanceScore()).build();
    }

    private EarthObservatorySimilarityResult toEarthObservatorySimilarityResult(EarthObservatorySimilarityResultResponse r) {
        if (r == null || r.date() == null || isBlank(r.title()) || isBlank(r.explanation()) || isBlank(r.mediaType()) || isBlank(r.url()) || isBlank(r.articleUrl()) || r.relevanceScore() == null) throw ApodException.invalidResponse(null);
        return EarthObservatorySimilarityResult.newBuilder().date(r.date()).title(r.title()).explanation(r.explanation()).mediaType(r.mediaType()).url(r.url())
                .credit(r.credit()).copyright(r.copyright()).imageDate(r.imageDate()).locationName(r.locationName()).latitude(r.latitude()).longitude(r.longitude()).articleUrl(r.articleUrl()).relevanceScore(r.relevanceScore()).build();
    }

    public Picture toPicture(ApodResponse response) {
        if (response == null || response.date() == null || isBlank(response.title())
                || isBlank(response.explanation()) || isBlank(response.mediaType())) {
            throw ApodException.invalidResponse(null);
        }
        return picture(response.date(), response.title(), response.explanation(), response.mediaType(),
                response.url(), response.credit(), response.copyright(), null, null);
    }

    public List<Picture> toPictures(ApodSearchResponse response) {
        if (response == null || isBlank(response.query()) || response.results() == null) {
            throw ApodException.invalidResponse(null);
        }
        return response.results().stream().map(this::toPicture).toList();
    }

    public List<SearchPicture> toSearchPictures(ApodSimilarityResponse response, LocalDate sourceDate) {
        if (response == null || !sourceDate.equals(response.date()) || response.results() == null) {
            throw ApodException.invalidResponse(null);
        }
        return response.results().stream().map(this::toSearchPicture).toList();
    }

    private Picture toPicture(ApodSearchResultResponse response) {
        if (response == null || response.relevanceScore() == null || !validScore(response.relevanceScore())
                || response.matchTypes() == null || response.matchTypes().isEmpty()) {
            throw ApodException.invalidResponse(null);
        }
        List<PictureMatchType> matchTypes;
        try {
            matchTypes = response.matchTypes().stream()
                    .map(value -> PictureMatchType.valueOf(value.toUpperCase(Locale.ROOT)))
                    .toList();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApodException.invalidResponse(exception);
        }
        return picture(response.date(), response.title(), response.explanation(), response.mediaType(),
                response.url(), response.credit(), response.copyright(),
                response.relevanceScore(), matchTypes);
    }

    private SearchPicture toSearchPicture(ApodSimilarityResultResponse response) {
        if (response == null || response.relevanceScore() == null || !validScore(response.relevanceScore())) {
            throw ApodException.invalidResponse(null);
        }
        validatePicture(response.date(), response.title(), response.explanation(), response.mediaType());
        return SearchPicture.newBuilder()
                .date(response.date()).title(response.title()).explanation(response.explanation()).mediaType(response.mediaType())
                .url(response.url() == null ? "" : response.url())
                .credit(response.credit()).copyright(response.copyright()).relevanceScore(response.relevanceScore())
                .matchTypes(null).build();
    }

    private Picture picture(
            LocalDate date, String title, String explanation, String mediaType, String url,
            String credit, String copyright, Double relevanceScore, List<PictureMatchType> matchTypes) {
        validatePicture(date, title, explanation, mediaType);
        return Picture.newBuilder()
                .date(date).title(title).explanation(explanation).mediaType(mediaType)
                .url(url == null ? "" : url)
                .credit(credit).copyright(copyright).relevanceScore(relevanceScore)
                .matchTypes(matchTypes).build();
    }

    private static void validatePicture(LocalDate date, String title, String explanation, String mediaType) {
        if (date == null || isBlank(title) || isBlank(explanation) || isBlank(mediaType)) {
            throw ApodException.invalidResponse(null);
        }
    }

    private static boolean validScore(double score) {
        return Double.isFinite(score) && score >= 0.0 && score <= 1.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
