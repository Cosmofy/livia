package xyz.arryan.livia.services;

import org.springframework.stereotype.Service;
import xyz.arryan.livia.clients.ArticlesClient;
import xyz.arryan.livia.clients.dto.ArticlePageResponse;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.mappers.ArticlesMapper;

import java.util.ArrayList;
import java.util.List;

@Service
public class ArticlesService {

    private static final int PAGE_SIZE = 100;

    private final ArticlesClient client;
    private final ArticlesMapper mapper;

    public ArticlesService(ArticlesClient client, ArticlesMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public List<Article> allArticles() {
        ArticlePageResponse firstPage = client.get(
                PAGE_SIZE, 0, null, null, null, null, "date");
        List<Article> articles = new ArrayList<>(mapper.toGraphQlArticles(firstPage));
        for (int offset = PAGE_SIZE; offset < firstPage.totalCount(); offset += PAGE_SIZE) {
            ArticlePageResponse page = client.get(
                    PAGE_SIZE, offset, null, null, null, null, "date");
            articles.addAll(mapper.toGraphQlArticles(page));
        }
        return List.copyOf(articles);
    }
}
