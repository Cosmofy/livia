package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import xyz.arryan.livia.codegen.types.Article;
import xyz.arryan.livia.codegen.types.Author;
import xyz.arryan.livia.codegen.types.Banner;

import java.util.List;
import java.util.stream.Collectors;

@DgsComponent
public class ArticlesDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(ArticlesDataFetcher.class);
    private static final String LOG_PREFIX = "API 2: ARTICLES | ";
    private static final String COLLECTION = "articles";

    private static String lp(String msg) { return LOG_PREFIX + msg; }

    private final MongoTemplate mongoTemplate;

    @Autowired
    public ArticlesDataFetcher(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        logger.info(lp("constructor: initialized with MongoDB backend"));
    }

    @DgsQuery
    public List<Article> articles() {
        logger.info(lp("entry: fetching articles from MongoDB"));

        try {
            List<Document> docs = mongoTemplate.findAll(Document.class, COLLECTION);

            if (docs.isEmpty()) {
                logger.warn(lp("MongoDB collection '{}' is empty"), COLLECTION);
                return List.of();
            }

            List<Article> articles = docs.stream()
                    .map(this::docToArticle)
                    .collect(Collectors.toList());

            logger.info(lp("fetch complete: loaded articles={} from MongoDB"), articles.size());
            return articles;
        } catch (Exception e) {
            logger.error(lp("MongoDB fetch failed: {}"), e.getMessage(), e);
            throw new RuntimeException("Failed to load articles data", e);
        }
    }

    private Article docToArticle(Document doc) {
        // Parse banner
        Banner banner = null;
        Document bannerDoc = doc.get("banner", Document.class);
        if (bannerDoc != null) {
            banner = Banner.newBuilder()
                    .image(bannerDoc.getString("image"))
                    .designer(bannerDoc.getString("designer"))
                    .build();
        }

        // Parse authors
        List<Author> authors = null;
        List<Document> authorsDocs = doc.getList("authors", Document.class);
        if (authorsDocs != null) {
            authors = authorsDocs.stream()
                    .map(a -> Author.newBuilder()
                            .name(a.getString("name"))
                            .title(a.getString("title"))
                            .image(a.getString("image"))
                            .build())
                    .collect(Collectors.toList());
        }

        return Article.newBuilder()
                .month(getInt(doc, "month"))
                .year(getInt(doc, "year"))
                .title(doc.getString("title"))
                .subtitle(doc.getString("subtitle"))
                .url(doc.getString("url"))
                .source(doc.getString("source"))
                .authors(authors)
                .banner(banner)
                .build();
    }

    private Integer getInt(Document doc, String key) {
        Object val = doc.get(key);
        if (val == null) return null;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Double) return ((Double) val).intValue();
        if (val instanceof Long) return ((Long) val).intValue();
        return null;
    }
}
