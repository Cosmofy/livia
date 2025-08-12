package xyz.arryan.livia.datafetchers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import xyz.arryan.livia.codegen.types.Article;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@DgsComponent
public class ArticlesDataFetcher {

    private final Gson gson;
    private final List<Article> cachedArticles;

    private static final Logger logger = LoggerFactory.getLogger(ArticlesDataFetcher.class);
    private static final String LOG_PREFIX = "API 2: ARTICLES | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }

    public ArticlesDataFetcher(Gson gson) {
        this.gson = gson;
        logger.info(lp("constructor: initializing cached articles from /articles.json"));
        final Instant t0 = Instant.now();
        List<Article> parsed;
        // Open and parse the static JSON resource once at startup
        try (InputStream inputStream = getClass().getResourceAsStream("/articles.json")) {
            if (inputStream == null) {
                logger.error(lp("resource missing: /articles.json not found on classpath"));
                throw new IllegalStateException("Resource /articles.json not found. Ensure the file exists in the resources directory.");
            }
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<Article>>() {}.getType();
                parsed = gson.fromJson(reader, listType);
            }
        } catch (Exception ex) {
            logger.error(lp("failed to load/parse /articles.json: {}"), ex.toString(), ex);
            throw ex instanceof IllegalStateException ? (IllegalStateException) ex : new IllegalStateException("Failed to initialize Articles cache from /articles.json", ex);
        }
        if (parsed == null) {
            logger.warn(lp("parsed articles list is null; defaulting to empty list"));
            parsed = List.of();
        }
        this.cachedArticles = parsed;
        final long ms = Duration.between(t0, Instant.now()).toMillis();
        logger.info(lp("constructor complete: loaded articles={} in {} ms"), cachedArticles.size(), ms);
    }
    @DgsQuery
    public List<Article> articles() {
        // Entry log for query execution
        logger.info(lp("query entry: articles() request received"));
        // DEBUG could dump first titles if ever needed (kept lean in production)
        // logger.debug(lp("articles sample: {}"), cachedArticles.stream().map(Article::getTitle).limit(3).toList());
        logger.info(lp("query return: count={}"), cachedArticles.size());
        return cachedArticles;
    }
}
