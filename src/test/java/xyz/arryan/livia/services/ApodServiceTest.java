package xyz.arryan.livia.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.ApodClient;
import xyz.arryan.livia.clients.dto.ApodResponse;
import xyz.arryan.livia.clients.dto.ApodSearchResponse;
import xyz.arryan.livia.codegen.types.Apod;
import xyz.arryan.livia.codegen.types.ApodSearchPayload;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.mappers.ApodMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApodServiceTest {

    private ApodClient client;
    private ApodService service;

    @BeforeEach
    void setUp() {
        client = mock(ApodClient.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T05:59:59Z"), ZoneOffset.UTC);
        service = new ApodService(client, new ApodMapper(), clock);
    }

    @Test
    void omittedDateLetsTheApodServiceResolveMountainTimeToday() {
        when(client.get(null)).thenReturn(response(LocalDate.of(2026, 9, 4)));

        Apod result = service.get(null);

        assertThat(result.getDate()).isEqualTo(LocalDate.of(2026, 9, 4));
        verify(client).get(null);
    }

    @Test
    void exactDateIsPassedToTheClient() {
        LocalDate date = LocalDate.of(2024, 2, 29);
        when(client.get(date)).thenReturn(response(date));

        Apod result = service.get(date);

        assertThat(result.getDate()).isEqualTo(date);
        verify(client).get(date);
    }

    @Test
    void rejectsAnUpstreamRecordWithTheWrongRequestedDate() {
        LocalDate requested = LocalDate.of(2024, 2, 29);
        when(client.get(requested)).thenReturn(response(LocalDate.of(2024, 3, 1)));

        assertCode(() -> service.get(requested), "APOD_INVALID_RESPONSE");
    }

    @Test
    void rejectsTooEarlyAndMountainTimeFutureDatesLocally() {
        assertCode(() -> service.get(LocalDate.of(1995, 6, 15)), "DATE_TOO_EARLY");
        assertCode(() -> service.get(LocalDate.of(2026, 9, 5)), "DATE_IN_FUTURE");
        verifyNoInteractions(client);
    }

    @Test
    void normalizesSearchAndAppliesTheDefaultLimit() {
        when(client.search("spiral galaxy", 10))
                .thenReturn(new ApodSearchResponse("spiral galaxy", "hybrid", List.of()));

        ApodSearchPayload result = service.search("  spiral   galaxy  ", null);

        assertThat(result.getQuery()).isEqualTo("spiral galaxy");
        assertThat(result.getResults()).isEmpty();
        verify(client).search("spiral galaxy", 10);
    }

    @Test
    void enforcesSearchQueryAndLimitBoundaries() {
        assertCode(() -> service.search("   ", 10), "INVALID_SEARCH_QUERY");
        assertCode(() -> service.search("___ !!!", 10), "INVALID_SEARCH_QUERY");
        assertCode(() -> service.search("x".repeat(201), 10), "INVALID_SEARCH_QUERY");
        assertCode(() -> service.search("galaxy", 0), "INVALID_SEARCH_QUERY");
        assertCode(() -> service.search("galaxy", 51), "INVALID_SEARCH_QUERY");
        verifyNoInteractions(client);
    }

    private static void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ApodException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static ApodResponse response(LocalDate date) {
        return new ApodResponse(
                date,
                "A title",
                "An explanation",
                "image",
                "https://example.com/apod.jpg",
                null,
                null,
                null);
    }
}
