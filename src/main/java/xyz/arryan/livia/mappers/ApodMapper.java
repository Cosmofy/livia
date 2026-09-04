package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResultResponse;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodMatchType;
import xyz.arryan.livia.codegen.types.ApodSearchMode;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.codegen.types.ApodSearchResult;
import xyz.arryan.livia.errors.ApodException;

import java.util.List;
import java.util.Locale;

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
                .credit(response.credit())
                .copyright(response.copyright())
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
                response.copyright()));
        return ApodSearchResult.newBuilder()
                .apod(apod)
                .relevanceScore(response.relevanceScore())
                .matchTypes(matchTypes)
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
