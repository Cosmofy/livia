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

@DgsComponent
public class ArticlesDataFetcher {

    private final Gson gson;

    public ArticlesDataFetcher(Gson gson) {
        this.gson = gson;
    }

    @DgsQuery
    public List<Article> articles() {
        InputStream inputStream = getClass().getResourceAsStream("/articles.json");
        if (inputStream == null) {
            throw new RuntimeException("Resource /articles.json not found");
        }
        InputStreamReader reader = new InputStreamReader(inputStream);
        Type listType = new TypeToken<List<Article>>() {}.getType();
        return gson.fromJson(reader, listType);
    }
}
