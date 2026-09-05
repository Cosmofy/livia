package xyz.arryan.livia;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.Author;
import xyz.arryan.livia.codegen.types.Banner;
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
        when(service.allArticles()).thenReturn(List.of(article()));

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
        verify(service).allArticles();
    }

    @Test
    void exposesOrdinaryGraphQlTypesWithoutFederationSchemaArtifacts() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = queryExecutor.executeAndExtractJsonPath(
                "{ __schema { types { name } } }", "data.__schema.types");
        List<String> typeNames = types.stream()
                .map(type -> String.valueOf(type.get("name")))
                .toList();

        assertThat(typeNames)
                .contains("Article", "Apod", "NewsArticle")
                .doesNotContain("ArticlePage", "ArticleOrdering", "_Any", "_Entity", "_Service", "_FieldSet", "link__Import");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queryFields = queryExecutor.executeAndExtractJsonPath(
                "{ __type(name: \"Query\") { fields { name } } }", "data.__type.fields");
        assertThat(queryFields)
                .extracting(field -> String.valueOf(field.get("name")))
                .contains("articles")
                .doesNotContain("article", "articlesPage", "_service", "_entities");
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
