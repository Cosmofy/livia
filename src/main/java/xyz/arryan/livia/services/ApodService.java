package xyz.arryan.livia.services;

import org.springframework.stereotype.Service;
import xyz.arryan.livia.clients.ApodClient;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodPicture;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.codegen.types.ApodSimilarityPayload;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.mappers.ApodMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Service
public class ApodService {

    static final LocalDate FIRST_APOD_DATE = LocalDate.of(1995, 6, 16);
    static final ZoneId MOUNTAIN_TIME = ZoneId.of("America/Denver");
    static final int DEFAULT_SEARCH_LIMIT = 10;

    private static final Pattern SEARCHABLE_CHARACTER = Pattern.compile("[\\p{L}\\p{N}]");

    private final ApodClient client;
    private final ApodMapper mapper;
    private final Clock clock;

    public ApodService(ApodClient client, ApodMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Apod get(LocalDate date) {
        if (date != null) {
            validateDate(date);
        }
        ApodResponse response = client.get(date);
        if (date != null && (response == null || !date.equals(response.date()))) {
            throw ApodException.invalidResponse(null);
        }
        return mapper.toGraphQl(response);
    }

    public ApodSearchPayload search(String query, Integer limit) {
        String normalizedQuery = normalizeSearchQuery(query);
        int resolvedLimit = limit == null ? DEFAULT_SEARCH_LIMIT : limit;
        if (resolvedLimit < 1 || resolvedLimit > 50) {
            throw ApodException.validation("INVALID_SEARCH_QUERY");
        }
        return mapper.toGraphQl(client.search(normalizedQuery, resolvedLimit));
    }

    public ApodPicture getPicture(LocalDate date) {
        return mapper.toPicture(get(date));
    }

    public ApodSimilarityPayload similar(LocalDate date, Integer limit) {
        int resolvedLimit = limit == null ? DEFAULT_SEARCH_LIMIT : limit;
        if (date == null || resolvedLimit < 1 || resolvedLimit > 50) {
            throw ApodException.validation("INVALID_SIMILARITY_REQUEST");
        }
        validateDate(date);
        var response = client.similar(date, resolvedLimit);
        if (response == null || !date.equals(response.date())
                || response.results() == null || response.results().size() > resolvedLimit) {
            throw ApodException.invalidResponse(null);
        }
        return mapper.toGraphQl(response);
    }

    private void validateDate(LocalDate date) {
        if (date.isBefore(FIRST_APOD_DATE)) {
            throw ApodException.validation("DATE_TOO_EARLY");
        }
        if (date.isAfter(LocalDate.now(clock.withZone(MOUNTAIN_TIME)))) {
            throw ApodException.validation("DATE_IN_FUTURE");
        }
    }

    private static String normalizeSearchQuery(String query) {
        if (query == null) {
            throw ApodException.validation("INVALID_SEARCH_QUERY");
        }
        String normalized = query.trim().replaceAll("\\s+", " ");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 200 || !SEARCHABLE_CHARACTER.matcher(normalized).find()) {
            throw ApodException.validation("INVALID_SEARCH_QUERY");
        }
        return normalized;
    }
}
