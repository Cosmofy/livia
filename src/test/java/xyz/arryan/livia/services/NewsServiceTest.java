package xyz.arryan.livia.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import xyz.arryan.livia.clients.NewsClient;
import xyz.arryan.livia.clients.dto.NewsPageResponse;
import xyz.arryan.livia.codegen.types.NewsOrdering;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.errors.NewsException;
import xyz.arryan.livia.mappers.NewsMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NewsServiceTest {

    private NewsClient client;
    private NewsService service;

    @BeforeEach
    void setUp() {
        client = mock(NewsClient.class);
        service = new NewsService(client, new NewsMapper());
    }

    @Test
    void appliesDefaultsAndOmitsOptionalFilters() {
        when(client.get(24, 0, null, "-published_at", null)).thenReturn(emptyPage(24, 0));

        NewsPage result = service.get(null, null, null, null, null);

        assertThat(result.getArticles()).isEmpty();
        verify(client).get(24, 0, null, "-published_at", null);
    }

    @Test
    void trimsFiltersAndPassesPagination() {
        when(client.get(50, 100, "Moon landing", "updated_at", "NASA,ESA"))
                .thenReturn(emptyPage(50, 100));

        service.get(50, 100, "  Moon landing  ", NewsOrdering.UPDATED_AT_ASCENDING, "  NASA,ESA  ");

        verify(client).get(50, 100, "Moon landing", "updated_at", "NASA,ESA");
    }

    @ParameterizedTest
    @CsvSource({
            "PUBLISHED_AT_ASCENDING,published_at",
            "PUBLISHED_AT_DESCENDING,-published_at",
            "UPDATED_AT_ASCENDING,updated_at",
            "UPDATED_AT_DESCENDING,-updated_at"
    })
    void mapsEveryOrderingExplicitly(NewsOrdering ordering, String transportValue) {
        assertThat(NewsService.toTransportOrdering(ordering)).isEqualTo(transportValue);
    }

    @Test
    void rejectsPaginationAndEmptyOptionalFiltersBeforeCallingNews() {
        assertInvalid(() -> service.get(0, 0, null, null, null));
        assertInvalid(() -> service.get(501, 0, null, null, null));
        assertInvalid(() -> service.get(24, -1, null, null, null));
        assertInvalid(() -> service.get(24, 0, "   ", null, null));
        assertInvalid(() -> service.get(24, 0, null, null, "   "));
        verifyNoInteractions(client);
    }

    private static NewsPageResponse emptyPage(int limit, int offset) {
        return new NewsPageResponse(0, limit, offset, false, offset > 0, List.of());
    }

    private static void assertInvalid(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(NewsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_QUERY"));
    }
}
