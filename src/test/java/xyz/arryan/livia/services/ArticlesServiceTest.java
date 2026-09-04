package xyz.arryan.livia.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import xyz.arryan.livia.clients.ArticlesClient;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.codegen.types.ArticleOrdering;
import xyz.arryan.livia.codegen.types.ArticlePage;
import xyz.arryan.livia.errors.ArticlesException;
import xyz.arryan.livia.mappers.ArticlesMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticlesServiceTest {

    private static final UUID ARTICLE_ID = UUID.fromString("e265a685-c093-5097-9337-fb1cdc73936c");
    private ArticlesClient client;
    private ArticlesService service;

    @BeforeEach
    void setUp() {
        client = mock(ArticlesClient.class);
        service = new ArticlesService(client, new ArticlesMapper());
    }

    @Test
    void appliesPageDefaultsAndOmitsOptionalFilters() {
        when(client.get(24, 0, null, null, null, null, "-date"))
                .thenReturn(emptyPage(24, 0));

        ArticlePage result = service.getPage(null, null, null, null, null, null, null);

        assertThat(result.getArticles()).isEmpty();
        verify(client).get(24, 0, null, null, null, null, "-date");
    }

    @Test
    void trimsFiltersAndPassesEveryFilter() {
        when(client.get(50, 100, "dark matter", 2026, 8, "Quanta", "title"))
                .thenReturn(emptyPage(50, 100));

        service.getPage(
                50, 100, "  dark matter  ", 2026, 8, "  Quanta  ",
                ArticleOrdering.TITLE_ASCENDING);

        verify(client).get(50, 100, "dark matter", 2026, 8, "Quanta", "title");
    }

    @ParameterizedTest
    @CsvSource({
            "DATE_ASCENDING,date",
            "DATE_DESCENDING,-date",
            "TITLE_ASCENDING,title",
            "TITLE_DESCENDING,-title"
    })
    void mapsEveryOrderingExplicitly(ArticleOrdering ordering, String transportValue) {
        assertThat(ArticlesService.toTransportOrdering(ordering)).isEqualTo(transportValue);
    }

    @Test
    void resolvesAnExactUuidWithoutScanningTheCollection() {
        ArticleResponse response = ArticlesMapperTestFixture.article();
        when(client.getById(ARTICLE_ID)).thenReturn(response);

        assertThat(service.getById(ARTICLE_ID.toString()).getId()).isEqualTo(ARTICLE_ID.toString());
        verify(client).getById(ARTICLE_ID);
    }

    @Test
    void preservesTheUnpaginatedLegacyQueryInAscendingDateOrder() {
        ArticleResponse article = ArticlesMapperTestFixture.article();
        when(client.get(100, 0, null, null, null, null, "date"))
                .thenReturn(new ArticlePageResponse(101, 100, 0, true, false, List.of(article)));
        when(client.get(100, 100, null, null, null, null, "date"))
                .thenReturn(new ArticlePageResponse(101, 100, 100, false, true, List.of(article)));

        assertThat(service.legacyArticles()).hasSize(2);
        verify(client).get(100, 0, null, null, null, null, "date");
        verify(client).get(100, 100, null, null, null, null, "date");
    }

    @Test
    void rejectsInvalidFiltersAndIdentifiersBeforeCallingArticles() {
        assertInvalid(() -> service.getPage(0, 0, null, null, null, null, null));
        assertInvalid(() -> service.getPage(101, 0, null, null, null, null, null));
        assertInvalid(() -> service.getPage(24, -1, null, null, null, null, null));
        assertInvalid(() -> service.getPage(24, 0, " ", null, null, null, null));
        assertInvalid(() -> service.getPage(24, 0, null, 1899, null, null, null));
        assertInvalid(() -> service.getPage(24, 0, null, 2101, null, null, null));
        assertInvalid(() -> service.getPage(24, 0, null, null, 0, null, null));
        assertInvalid(() -> service.getPage(24, 0, null, null, 13, null, null));
        assertInvalid(() -> service.getPage(24, 0, null, null, null, " ", null));
        assertInvalid(() -> service.getById("not-a-uuid"));
        verifyNoInteractions(client);
    }

    private static ArticlePageResponse emptyPage(int limit, int offset) {
        return new ArticlePageResponse(0, limit, offset, false, offset > 0, List.of());
    }

    private static void assertInvalid(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ArticlesException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_QUERY"));
    }

    private static final class ArticlesMapperTestFixture {
        private static ArticleResponse article() {
            return new ArticleResponse(
                    ARTICLE_ID,
                    8,
                    2026,
                    "Example title",
                    "Example subtitle",
                    "https://publisher.example/article",
                    "Quanta Magazine",
                    new xyz.arryan.livia.clients.dto.ArticleBannerResponse(
                            "https://publisher.example/banner.jpg", "Designer Name"),
                    List.of(new xyz.arryan.livia.clients.dto.ArticleAuthorResponse(
                            "Author Name", "Staff Writer", "https://publisher.example/author.jpg")));
        }
    }
}
