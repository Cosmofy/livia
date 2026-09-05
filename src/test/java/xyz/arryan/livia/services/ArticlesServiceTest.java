package xyz.arryan.livia.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.ArticlesClient;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.mappers.ArticlesMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void returnsEveryArticleAcrossAllMicroservicePagesInAscendingDateOrder() {
        ArticleResponse article = ArticlesMapperTestFixture.article();
        when(client.get(100, 0, null, null, null, null, "date"))
                .thenReturn(new ArticlePageResponse(101, 100, 0, true, false, List.of(article)));
        when(client.get(100, 100, null, null, null, null, "date"))
                .thenReturn(new ArticlePageResponse(101, 100, 100, false, true, List.of(article)));

        assertThat(service.allArticles()).hasSize(2);
        verify(client).get(100, 0, null, null, null, null, "date");
        verify(client).get(100, 100, null, null, null, null, "date");
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
