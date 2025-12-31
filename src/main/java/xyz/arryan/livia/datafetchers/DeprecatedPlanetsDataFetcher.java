package xyz.arryan.livia.datafetchers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.arryan.livia.codegen.types.Planet;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@DgsComponent
@Deprecated
public class DeprecatedPlanetsDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(DeprecatedPlanetsDataFetcher.class);
    private static final String LOG_PREFIX = "PLANETS (DEPRECATED) | ";

    private final List<Planet> planetsData;

    public DeprecatedPlanetsDataFetcher() {
        this.planetsData = loadPlanetsJson();
        logger.info(LOG_PREFIX + "Initialized with {} planets from planets.json", planetsData.size());
    }

    private List<Planet> loadPlanetsJson() {
        Gson gson = new Gson();
        try (InputStream is = getClass().getResourceAsStream("/planets.json");
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<Planet>>(){}.getType();
            return gson.fromJson(reader, listType);
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to load planets.json: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @DgsQuery
    public List<Planet> planets(@InputArgument List<String> names) {
        logger.info(LOG_PREFIX + "Query: planets(names={})", names);

        if (names == null || names.isEmpty()) {
            return planetsData;
        }

        // Filter out empty strings
        List<String> filtered = names.stream()
                .filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            return planetsData;
        }

        return planetsData.stream()
                .filter(p -> filtered.stream().anyMatch(n -> n.equalsIgnoreCase(p.getName())))
                .collect(Collectors.toList());
    }
}
