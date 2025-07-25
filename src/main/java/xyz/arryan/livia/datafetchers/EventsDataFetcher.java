package xyz.arryan.livia.datafetchers;


import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import graphql.GraphQLException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.gson.Gson;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.codegen.types.Category;
import xyz.arryan.livia.codegen.types.Event;
import xyz.arryan.livia.codegen.types.Source;
import xyz.arryan.livia.codegen.types.Geometry;


import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@DgsComponent
public class EventsDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(EventsDataFetcher.class);

    private WebClient webClient;
    private final Gson gson;

    @Autowired
    public EventsDataFetcher(Gson gson, WebClient webClient) {
        this.gson = gson;
        this.webClient = webClient;
    }

    @DgsQuery
    public List<Event> events(@InputArgument Integer days) {
        logger.info("Fetching events for last {} days", days);

        // A. Input Validation
        ZoneId mountainTime = ZoneId.of("America/Denver");
        if (days == null) { // if date is empty set -> today's date (mountain time)
            days = 14;
            logger.info("Days was set to null, resolving America/Denver time zone, set days to: {}", days);
        }

        ZonedDateTime daysAgoDate = ZonedDateTime.now(mountainTime).minusDays(days);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String formattedDate = daysAgoDate.format(formatter);

        LocalDate minDate = LocalDate.of(2022, 1, 1); // minimum starting ping for nasa eonet
        LocalDate maxDate = LocalDate.now(mountainTime); // ending date is today's date, 2 hours after API should be updated.
        if (daysAgoDate.toLocalDate().isBefore(minDate) || daysAgoDate.toLocalDate().isAfter(maxDate)) {
            logger.error("Input date should be before max date or before min date {}", formattedDate);
            throw new GraphQLException("Date must be between 2022-01-01 and " + maxDate);
        }

        // B. Fetch valid input from API
        String api_source = "https://eonet.gsfc.nasa.gov/api/v3/events?start=" + formattedDate + "&end=2029-12-31&status=all";
        String response = webClient
                .get()
                .uri(api_source)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonObject root = gson.fromJson(response, JsonObject.class);
        if (root == null) {
            logger.error("Could not parse response from API");
            throw new GraphQLException("API Fetch failed.");
        }
        JsonArray eventsArray = root.getAsJsonArray("events");

        // Extract only the fields specified in the GraphQL schema
        Type rawListType = new TypeToken<List<Map<String, Object>>>() {}.getType();
        List<Map<String, Object>> rawEvents = gson.fromJson(eventsArray, rawListType);

        List<Event> finalEvents = new ArrayList<>();
        for (Map<String, Object> eventMap : rawEvents) {
            // Categories
            List<Map<String, String>> categoryListRaw = (List<Map<String, String>>) eventMap.get("categories");
            List<Category> categories = new ArrayList<>();
            if (categoryListRaw != null) {
                for (Map<String, String> catMap : categoryListRaw) {
                    Category category = Category.newBuilder()
                            .id(catMap.get("id"))
                            .title(catMap.get("title"))
                            .build();
                    categories.add(category);
                }
            }

            // Sources
            List<Map<String, String>> sourceListRaw = (List<Map<String, String>>) eventMap.get("sources");
            List<Source> sources = new ArrayList<>();
            if (sourceListRaw != null) {
                for (Map<String, String> srcMap : sourceListRaw) {
                    Source source = Source.newBuilder()
                            .id(srcMap.get("id"))
                            .url(srcMap.get("url"))
                            .build();
                    sources.add(source);
                }
            }

            // Geometry
            List<Map<String, Object>> geometryListRaw = (List<Map<String, Object>>) eventMap.get("geometry");
            List<Geometry> geometries = new ArrayList<>();
            if (geometryListRaw != null) {
                for (Map<String, Object> geoMap : geometryListRaw) {
                    Float magnitudeValue = null;
                    Object val = geoMap.get("magnitudeValue");
                    if (val instanceof Number number) {
                        magnitudeValue = number.floatValue();
                    }

                    List<Double> coords = (List<Double>) geoMap.get("coordinates");

                    Geometry geometry = Geometry.newBuilder()
                            .magnitudeValue(magnitudeValue != null ? Double.valueOf(magnitudeValue) : null)
                            .magnitudeUnit((String) geoMap.get("magnitudeUnit"))
                            .date((String) geoMap.get("date"))
                            .type((String) geoMap.get("type"))
                            .coordinates(coords)
                            .build();
                    geometries.add(geometry);
                }
            }

            // Build event
            Event event = Event.newBuilder()
                    .id((String) eventMap.get("id"))
                    .title((String) eventMap.get("title"))
                    .categories(categories)
                    .sources(sources)
                    .geometry(geometries)
                    .build();

            finalEvents.add(event);
        }

        return finalEvents;
    }

}
