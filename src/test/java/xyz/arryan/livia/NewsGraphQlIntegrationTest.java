package xyz.arryan.livia;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.arryan.livia.codegen.types.NewsArticle;
import xyz.arryan.livia.codegen.types.NewsOrdering;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.errors.NewsException;
import xyz.arryan.livia.services.NewsService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
})
class NewsGraphQlIntegrationTest {

    @Autowired
    private DgsQueryExecutor queryExecutor;

    @MockitoBean
    private NewsService service;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Test
    void resolvesTheExactIosOperationAndDefaultArguments() {
        when(service.get(24, 0, null, NewsOrdering.PUBLISHED_AT_DESCENDING, null))
                .thenReturn(page());
        Map<String, Object> variables = Map.of(
                "limit", 24,
                "offset", 0,
                "ordering", "PUBLISHED_AT_DESCENDING");

        ExecutionResult result = queryExecutor.execute("""
                query LatestNews($limit: Int!, $offset: Int!, $ordering: NewsOrdering!) {
                  news(limit: $limit, offset: $offset, ordering: $ordering) {
                    totalCount
                    articles { id title summary url imageUrl newsSite publishedAt }
                  }
                }
                """, variables);

        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = result.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> news = (Map<String, Object>) data.get("news");
        assertThat(news.get("totalCount")).isEqualTo(35_942);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articles = (List<Map<String, Object>>) news.get("articles");
        assertThat(articles.getFirst())
                .containsEntry("id", "39822")
                .containsEntry("newsSite", "NASA")
                .containsEntry("publishedAt", "2026-09-03T15:59:48.000Z");
        verify(service).get(24, 0, null, NewsOrdering.PUBLISHED_AT_DESCENDING, null);
    }

    @Test
    void schemaDefaultsReachTheResolver() {
        when(service.get(24, 0, null, NewsOrdering.PUBLISHED_AT_DESCENDING, null))
                .thenReturn(page());

        Integer total = queryExecutor.executeAndExtractJsonPath(
                "query LatestNews { news { totalCount } }", "data.news.totalCount");

        assertThat(total).isEqualTo(35_942);
        verify(service).get(24, 0, null, NewsOrdering.PUBLISHED_AT_DESCENDING, null);
    }

    @Test
    void exposesSafeNewsErrorExtensions() {
        when(service.get(24, 0, null, NewsOrdering.PUBLISHED_AT_DESCENDING, null))
                .thenThrow(NewsException.upstream("RATE_LIMITED", "NEWS_UPSTREAM_ERROR", 429, 60L));

        ExecutionResult result = queryExecutor.execute("query LatestNews { news { totalCount } }");

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().getExtensions())
                .containsEntry("code", "RATE_LIMITED")
                .containsEntry("service", "news")
                .containsEntry("upstreamStatus", 429)
                .containsEntry("retryAfterSeconds", 60L)
                .containsKey("requestId");
        assertThat(result.getErrors().getFirst().getMessage())
                .isEqualTo("Too many news requests were made. Please try again shortly.");
    }

    private static NewsPage page() {
        NewsArticle article = NewsArticle.newBuilder()
                .id("39822")
                .title("Example title")
                .summary("Example summary")
                .url("https://publisher.example/article")
                .imageUrl("https://publisher.example/image.jpg")
                .newsSite("NASA")
                .authors(List.of("Author Name"))
                .publishedAt(OffsetDateTime.parse("2026-09-03T15:59:48Z"))
                .updatedAt(OffsetDateTime.parse("2026-09-03T16:00:00Z"))
                .featured(false)
                .launchIds(List.of())
                .eventIds(List.of())
                .build();
        return NewsPage.newBuilder()
                .totalCount(35_942)
                .limit(24)
                .offset(0)
                .hasNextPage(true)
                .hasPreviousPage(false)
                .articles(List.of(article))
                .build();
    }
}
