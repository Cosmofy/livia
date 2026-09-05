package xyz.arryan.livia.mappers;

import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.dto.ArticleAuthorResponse;
import xyz.arryan.livia.clients.dto.ArticleBannerResponse;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.clients.dto.ArticleResponse;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.errors.ArticlesException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticlesMapperTest {

    private static final UUID ARTICLE_ID = UUID.fromString("e265a685-c093-5097-9337-fb1cdc73936c");
    private final ArticlesMapper mapper = new ArticlesMapper();

    @Test
    void validatesThePageAndMapsEveryArticle() {
        List<Article> articles = mapper.toGraphQlArticles(new ArticlePageResponse(
                27, 24, 0, true, false, List.of(article())));

        Article result = articles.getFirst();
        assertThat(result.getId()).isEqualTo(ARTICLE_ID.toString());
        assertThat(result.getMonth()).isEqualTo(8);
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getTitle()).isEqualTo("Example title");
        assertThat(result.getSubtitle()).isEqualTo("Example subtitle");
        assertThat(result.getUrl()).isEqualTo("https://publisher.example/article");
        assertThat(result.getSource()).isEqualTo("Quanta Magazine");
        assertThat(result.getBanner().getImage()).isEqualTo("https://publisher.example/banner.jpg");
        assertThat(result.getBanner().getDesigner()).isEqualTo("Designer Name");
        assertThat(result.getAuthors()).singleElement().satisfies(author -> {
            assertThat(author.getName()).isEqualTo("Author Name");
            assertThat(author.getTitle()).isEqualTo("Staff Writer");
            assertThat(author.getImage()).isEqualTo("https://publisher.example/author.jpg");
        });
    }

    @Test
    void rejectsMissingServiceOwnedFieldsAndInvalidPagination() {
        ArticleResponse missingId = new ArticleResponse(
                null, 8, 2026, "Title", "Subtitle", "https://example.com", "Source",
                new ArticleBannerResponse("https://example.com/banner.jpg", "Designer"),
                List.of(new ArticleAuthorResponse("Author", "Writer", "https://example.com/author.jpg")));

        assertInvalid(() -> mapper.toGraphQl(missingId));
        ArticleResponse missingAuthors = new ArticleResponse(
                ARTICLE_ID, 8, 2026, "Title", "Subtitle", "https://example.com", "Source",
                new ArticleBannerResponse("https://example.com/banner.jpg", "Designer"), List.of());
        assertInvalid(() -> mapper.toGraphQl(missingAuthors));
        assertInvalid(() -> mapper.toGraphQlArticles(new ArticlePageResponse(
                -1, 0, -1, null, false, null)));
    }

    static ArticleResponse article() {
        return new ArticleResponse(
                ARTICLE_ID,
                8,
                2026,
                "Example title",
                "Example subtitle",
                "https://publisher.example/article",
                "Quanta Magazine",
                new ArticleBannerResponse("https://publisher.example/banner.jpg", "Designer Name"),
                List.of(new ArticleAuthorResponse(
                        "Author Name", "Staff Writer", "https://publisher.example/author.jpg")));
    }

    private static void assertInvalid(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ArticlesException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ARTICLES_INVALID_RESPONSE"));
    }
}
