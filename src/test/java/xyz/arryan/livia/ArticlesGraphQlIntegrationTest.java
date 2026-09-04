package xyz.arryan.livia;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.ArticleOrdering;
import xyz.arryan.livia.codegen.types.ArticlePage;
import xyz.arryan.livia.codegen.types.Author;
import xyz.arryan.livia.codegen.types.Banner;
import xyz.arryan.livia.errors.ArticlesException;
import xyz.arryan.livia.services.ArticlesService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
})
class ArticlesGraphQlIntegrationTest {

    private static final String ARTICLE_ID = "e265a685-c093-5097-9337-fb1cdc73936c";

    @Autowired
    private DgsQueryExecutor queryExecutor;

    @MockitoBean
    private ArticlesService service;

    @MockitoBean
    private MongoTemplate mongoTemplate;

    @Test
    void preservesTheExistingIosArticlesQueryAndAddsTheStableId() {
        when(service.legacyArticles()).thenReturn(List.of(article()));

        ExecutionResult result = queryExecutor.execute("""
                query ExistingArticles {
                  articles {
                    id month year title subtitle url source
                    banner { image designer }
                    authors { name title image }
                  }
                }
                """);

        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = result.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articleRows = (List<Map<String, Object>>) data.get("articles");
        assertThat(articleRows.getFirst())
                .containsEntry("id", ARTICLE_ID)
                .containsEntry("month", 8)
                .containsEntry("title", "Example title")
                .containsEntry("source", "Quanta Magazine");
        verify(service).legacyArticles();
    }

    @Test
    void pageSchemaDefaultsReachTheResolver() {
        when(service.getPage(24, 0, null, null, null, null, ArticleOrdering.DATE_DESCENDING))
                .thenReturn(page());

        Integer total = queryExecutor.executeAndExtractJsonPath(
                "query ArticlesPage { articlesPage { totalCount articles { id } } }",
                "data.articlesPage.totalCount");

        assertThat(total).isEqualTo(27);
        verify(service).getPage(24, 0, null, null, null, null, ArticleOrdering.DATE_DESCENDING);
    }

    @Test
    void exposesPaginationFiltersAndOrdering() {
        when(service.getPage(10, 20, "dark matter", 2026, 8, "Quanta", ArticleOrdering.TITLE_ASCENDING))
                .thenReturn(page());
        Map<String, Object> variables = Map.of(
                "limit", 10,
                "offset", 20,
                "search", "dark matter",
                "year", 2026,
                "month", 8,
                "source", "Quanta",
                "ordering", "TITLE_ASCENDING");

        ExecutionResult result = queryExecutor.execute("""
                query FilterArticles(
                  $limit: Int!, $offset: Int!, $search: String!, $year: Int!,
                  $month: Int!, $source: String!, $ordering: ArticleOrdering!
                ) {
                  articlesPage(
                    limit: $limit, offset: $offset, search: $search, year: $year,
                    month: $month, source: $source, ordering: $ordering
                  ) { totalCount limit offset hasNextPage hasPreviousPage articles { id } }
                }
                """, variables);

        assertThat(result.getErrors()).isEmpty();
        verify(service).getPage(10, 20, "dark matter", 2026, 8, "Quanta", ArticleOrdering.TITLE_ASCENDING);
    }

    @Test
    void resolvesExactArticleLookup() {
        when(service.getById(ARTICLE_ID)).thenReturn(article());

        String title = queryExecutor.executeAndExtractJsonPath(
                "query ExactArticle { article(id: \"" + ARTICLE_ID + "\") { id title } }",
                "data.article.title");

        assertThat(title).isEqualTo("Example title");
        verify(service).getById(ARTICLE_ID);
    }

    @Test
    void exposesSafeArticleErrorExtensions() {
        when(service.getById(ARTICLE_ID))
                .thenThrow(ArticlesException.upstream(
                        "ARTICLE_NOT_FOUND", "ARTICLES_UPSTREAM_ERROR", 404, null));

        ExecutionResult result = queryExecutor.execute(
                "query ExactArticle { article(id: \"" + ARTICLE_ID + "\") { id } }");

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().getExtensions())
                .containsEntry("code", "ARTICLE_NOT_FOUND")
                .containsEntry("service", "articles")
                .containsEntry("upstreamStatus", 404)
                .containsKey("requestId");
        assertThat(result.getErrors().getFirst().getMessage())
                .isEqualTo("The requested article was not found.");
    }

    @Test
    void exposesArticleAsAnOrdinaryGraphQlTypeWithoutEntityFederationTypes() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = queryExecutor.executeAndExtractJsonPath(
                "{ __schema { types { name } } }", "data.__schema.types");
        List<String> typeNames = types.stream()
                .map(type -> String.valueOf(type.get("name")))
                .toList();

        assertThat(typeNames)
                .contains("Article", "ArticlePage", "Apod", "NewsArticle")
                .doesNotContain("_Any", "_Entity", "link__Import");
    }

    private static ArticlePage page() {
        return ArticlePage.newBuilder()
                .totalCount(27)
                .limit(24)
                .offset(0)
                .hasNextPage(true)
                .hasPreviousPage(false)
                .articles(List.of(article()))
                .build();
    }

    private static Article article() {
        return Article.newBuilder()
                .id(ARTICLE_ID)
                .month(8)
                .year(2026)
                .title("Example title")
                .subtitle("Example subtitle")
                .url("https://publisher.example/article")
                .source("Quanta Magazine")
                .banner(Banner.newBuilder()
                        .image("https://publisher.example/banner.jpg")
                        .designer("Designer Name")
                        .build())
                .authors(List.of(Author.newBuilder()
                        .name("Author Name")
                        .title("Staff Writer")
                        .image("https://publisher.example/author.jpg")
                        .build()))
                .build();
    }
}
