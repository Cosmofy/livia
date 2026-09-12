package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
import xyz.arryan.livia.clients.dto.ApodSimilarityResponse;
import xyz.arryan.livia.codegen.types.ApodSimilarityPayload;
import xyz.arryan.livia.codegen.types.ApodSimilarityResult;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodPicture;
import xyz.arryan.livia.codegen.types.ApodMatchType;
import xyz.arryan.livia.codegen.types.ApodSearchMode;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.codegen.types.ApodSearchResult;
import xyz.arryan.livia.errors.ApodException;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.time.LocalDate;

@Component
public class ApodMapper {

    public Apod toGraphQl(ApodResponse response) {
        if (response == null
                || response.date() == null
                || isBlank(response.title())
                || isBlank(response.explanation())
                || isBlank(response.mediaType())) {
            throw ApodException.invalidResponse(null);
        }

        return Apod.newBuilder()
                .date(response.date())
                .title(response.title())
                .explanation(response.explanation())
                .mediaType(response.mediaType())
                .url(response.url() == null ? "" : response.url())
                .hdUrl(response.hdurl())
                .fallbackUrl(response.fallbackUrl())
                .credit(response.credit())
                .copyright(response.copyright())
                .build();
    }

    public ApodPicture toPicture(Apod apod) {
        return ApodPicture.newBuilder()
                .date(apod.getDate())
                .title(apod.getTitle())
                .explanation(apod.getExplanation())
                .mediaType(apod.getMediaType())
                .url(apod.getUrl())
                .hdUrl(apod.getHdUrl())
                .fallbackUrl(apod.getFallbackUrl())
                .credit(apod.getCredit())
                .copyright(apod.getCopyright())
                .build();
    }

    public ApodSearchPayload toGraphQl(ApodSearchResponse response) {
        if (response == null
                || isBlank(response.query())
                || response.searchMode() == null
                || response.results() == null) {
            throw ApodException.invalidResponse(null);
        }

        final ApodSearchMode searchMode;
        try {
            searchMode = ApodSearchMode.valueOf(response.searchMode().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ApodException.invalidResponse(exception);
        }

        List<ApodSearchResult> results = response.results().stream()
                .map(this::toSearchResult)
                .toList();
        return ApodSearchPayload.newBuilder()
                .query(response.query())
                .searchMode(searchMode)
                .results(results)
                .build();
    }

    private ApodSearchResult toSearchResult(ApodSearchResultResponse response) {
        if (response == null
                || response.relevanceScore() == null
                || !Double.isFinite(response.relevanceScore())
                || response.relevanceScore() < 0.0
                || response.relevanceScore() > 1.0
                || response.matchTypes() == null
                || response.matchTypes().isEmpty()) {
            throw ApodException.invalidResponse(null);
        }

        List<ApodMatchType> matchTypes;
        try {
            matchTypes = response.matchTypes().stream()
                    .map(value -> ApodMatchType.valueOf(value.toUpperCase(Locale.ROOT)))
                    .toList();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApodException.invalidResponse(exception);
        }

        Apod apod = toGraphQl(new ApodResponse(
                response.date(),
                response.title(),
                response.explanation(),
                response.mediaType(),
                response.url(),
                response.hdurl(),
                response.credit(),
                response.copyright(),
                response.fallbackUrl()));
        return ApodSearchResult.newBuilder()
                .date(apod.getDate())
                .title(apod.getTitle())
                .explanation(apod.getExplanation())
                .mediaType(apod.getMediaType())
                .url(apod.getUrl())
                .hdUrl(apod.getHdUrl())
                .credit(apod.getCredit())
                .copyright(apod.getCopyright())
                .relevanceScore(response.relevanceScore())
                .fallbackUrl(apod.getFallbackUrl())
                .matchTypes(matchTypes)
                .build();
    }

    public ApodSimilarityPayload toGraphQl(ApodSimilarityResponse response) {
        if (response == null || response.date() == null || response.results() == null) {
            throw ApodException.invalidResponse(null);
        }
        var dates = new HashSet<LocalDate>();
        dates.add(response.date());
        List<ApodSimilarityResult> results = new ArrayList<>();
        double previousScore = 1.0;
        for (var result : response.results()) {
            if (result == null || result.relevanceScore() == null
                    || !Double.isFinite(result.relevanceScore())
                    || result.relevanceScore() < 0.0 || result.relevanceScore() > previousScore
                    || !dates.add(result.date())) {
                throw ApodException.invalidResponse(null);
            }
            Apod picture = toGraphQl(new ApodResponse(result.date(), result.title(), result.explanation(),
                    result.mediaType(), result.url(), result.hdurl(), result.credit(), result.copyright(), result.fallbackUrl()));
            results.add(ApodSimilarityResult.newBuilder()
                    .date(picture.getDate()).title(picture.getTitle()).explanation(picture.getExplanation())
                    .mediaType(picture.getMediaType()).url(picture.getUrl()).hdUrl(picture.getHdUrl())
                    .credit(picture.getCredit()).copyright(picture.getCopyright())
                    .fallbackUrl(picture.getFallbackUrl())
                    .relevanceScore(result.relevanceScore()).build());
            previousScore = result.relevanceScore();
        }
        return ApodSimilarityPayload.newBuilder().date(response.date()).results(List.copyOf(results)).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
