package xyz.arryan.livia.mappers;

import org.junit.jupiter.api.Test;
import xyz.arryan.livia.clients.dto.NewsArticleResponse;
import xyz.arryan.livia.clients.dto.NewsPageResponse;
import xyz.arryan.livia.codegen.types.NewsArticle;
import xyz.arryan.livia.codegen.types.NewsPage;
import xyz.arryan.livia.errors.NewsException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsMapperTest {

    private final NewsMapper mapper = new NewsMapper();

    @Test
    void mapsEveryFieldAndPreservesEmptyArrays() {
        NewsPage page = mapper.toGraphQl(new NewsPageResponse(
                35_942, 24, 0, true, false, List.of(article(List.of(), List.of(), List.of()))));

        assertThat(page.getTotalCount()).isEqualTo(35_942);
        assertThat(page.getLimit()).isEqualTo(24);
        assertThat(page.getOffset()).isZero();
        assertThat(page.getHasNextPage()).isTrue();
        assertThat(page.getHasPreviousPage()).isFalse();
        NewsArticle article = page.getArticles().getFirst();
        assertThat(article.getId()).isEqualTo("39822");
        assertThat(article.getTitle()).isEqualTo("Example title");
        assertThat(article.getSummary()).isEqualTo("Example summary");
        assertThat(article.getUrl()).isEqualTo("https://publisher.example/article");
        assertThat(article.getImageUrl()).isEqualTo("https://publisher.example/image.jpg");
        assertThat(article.getNewsSite()).isEqualTo("NASA");
        assertThat(article.getAuthors()).isEmpty();
        assertThat(article.getPublishedAt()).isEqualTo(OffsetDateTime.parse("2026-09-03T15:59:48Z"));
        assertThat(article.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-09-03T16:00:00Z"));
        assertThat(article.getFeatured()).isFalse();
        assertThat(article.getLaunchIds()).isEmpty();
        assertThat(article.getEventIds()).isEmpty();
    }

    @Test
    void convertsProviderIdsToGraphQlIds() {
        NewsPage page = mapper.toGraphQl(new NewsPageResponse(
                1, 1, 0, false, false,
                List.of(article(List.of("Author"), List.of("123e4567-e89b-12d3-a456-426614174000"), List.of(9L)))));

        NewsArticle article = page.getArticles().getFirst();
        assertThat(article.getAuthors()).containsExactly("Author");
        assertThat(article.getLaunchIds()).containsExactly("123e4567-e89b-12d3-a456-426614174000");
        assertThat(article.getEventIds()).containsExactly("9");
    }

    @Test
    void rejectsMissingNonNullFieldsAndInvalidPagination() {
        NewsArticleResponse missingTimestamp = new NewsArticleResponse(
                1L, "Title", "Summary", "https://example.com", "https://example.com/image.jpg",
                "NASA", List.of(), null, OffsetDateTime.parse("2026-09-03T16:00:00Z"), false,
                List.of(), List.of());

        assertInvalid(() -> mapper.toGraphQl(new NewsPageResponse(
                1, 1, 0, false, false, List.of(missingTimestamp))));
        assertInvalid(() -> mapper.toGraphQl(new NewsPageResponse(
                -1, 0, -1, null, false, null)));
    }

    private static NewsArticleResponse article(
            List<String> authors,
            List<String> launchIds,
            List<Long> eventIds) {
        return new NewsArticleResponse(
                39822L,
                "Example title",
                "Example summary",
                "https://publisher.example/article",
                "https://publisher.example/image.jpg",
                "NASA",
                authors,
                OffsetDateTime.parse("2026-09-03T15:59:48Z"),
                OffsetDateTime.parse("2026-09-03T16:00:00Z"),
                false,
                launchIds,
                eventIds);
    }

    private static void assertInvalid(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(NewsException.class,
                        exception -> assertThat(exception.code()).isEqualTo("NEWS_INVALID_RESPONSE"));
    }
}
