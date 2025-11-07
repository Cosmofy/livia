package xyz.arryan.livia.datafetchers;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import xyz.arryan.livia.codegen.types.Planet;
import org.springframework.cache.annotation.Cacheable;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.List;

@DgsComponent
public class PlanetsDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(PlanetsDataFetcher.class);
    private static final String LOG_PREFIX = "API 4: PLANETS | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }
    private final MongoTemplate mongoTemplate;
    private final Gson gson;

    @Autowired
    public PlanetsDataFetcher(MongoTemplate mongoTemplate, Gson gson) {
        this.mongoTemplate = mongoTemplate;
        this.gson = gson;
        logger.info(lp("constructor: initialized with MongoDB backend"));
    }

    @PostConstruct
    public void initializePlanetsData() {
        logger.info(lp("initialization: checking if MongoDB needs to be populated"));
        try {
            long count = mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), "planets");

            if (count == 0) {
                logger.warn(lp("MongoDB 'planets' collection is empty, importing from JSON file"));
                List<Planet> planets = loadFromJsonFile();

                // Convert Planet objects to BSON documents and insert
                for (Planet planet : planets) {
                    String json = gson.toJson(planet);
                    org.bson.Document doc = org.bson.Document.parse(json);
                    mongoTemplate.insert(doc, "planets");
                }

                logger.info(lp("successfully imported {} planets from JSON to MongoDB"), planets.size());
            } else {
                logger.info(lp("MongoDB already contains {} planets, skipping import"), count);
            }
        } catch (Exception e) {
            logger.error(lp("failed to initialize planets data: {}"), e.getMessage(), e);
        }
    }

    @DgsQuery
    @Cacheable(value = "planets", key = "'all'")
    public List<Planet> planets() {
        logger.info(lp("entry: fetching planets from MongoDB"));

        try {
            // Fetch all planets from MongoDB
            List<org.bson.Document> documents = mongoTemplate.findAll(org.bson.Document.class, "planets");

            if (documents.isEmpty()) {
                logger.error(lp("MongoDB collection 'planets' is empty"));
                throw new RuntimeException("Planets data not available");
            }

            // Convert BSON documents to Planet objects
            String json = documents.toString();
            Type listType = new TypeToken<List<Planet>>() {}.getType();
            List<Planet> planets = gson.fromJson(json, listType);

            logger.info(lp("fetch complete: loaded planets={} from MongoDB"), planets.size());
            return planets;
        } catch (Exception e) {
            logger.error(lp("MongoDB fetch failed: {}"), e.getMessage(), e);
            throw new RuntimeException("Failed to load planets data", e);
        }
    }

    private List<Planet> loadFromJsonFile() {
        logger.info(lp("loading planets from JSON file"));
        try (InputStream is = getClass().getResourceAsStream("/planets.json");
             InputStreamReader reader = new InputStreamReader(is)) {
            Type listType = new TypeToken<List<Planet>>() {}.getType();
            List<Planet> planets = gson.fromJson(reader, listType);
            logger.info(lp("loaded planets={} from JSON file"), planets.size());
            return planets;
        } catch (IOException e) {
            logger.error(lp("failed to load planets from JSON file: {}"), e.getMessage(), e);
            throw new RuntimeException("Failed to load planets data", e);
        }
    }

}
