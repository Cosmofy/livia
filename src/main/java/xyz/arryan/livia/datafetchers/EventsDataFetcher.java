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
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@DgsComponent
public class EventsDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(EventsDataFetcher.class);

    private static final String LOG_PREFIX = "[API 2: EVENTS] | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }

    private WebClient webClient;
    private final Gson gson;

    @Autowired
    public EventsDataFetcher(Gson gson, WebClient webClient) {
        this.gson = gson;
        // Increase WebClient buffer to handle large EONET responses (default is 256 KiB)
        final int maxInMemoryBytes = 10 * 1024 * 1024; // 10 MiB
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(maxInMemoryBytes))
                .build();
        this.webClient = webClient.mutate().exchangeStrategies(strategies).build();
        logger.info(lp("WebClient configured: maxInMemorySize={} bytes"), maxInMemoryBytes);
    }

    @DgsQuery
    @Cacheable(value = "events", key = "'current'")
    public List<Event> events(@InputArgument Optional<Integer> daysInput) {
        // ===== A. Input logging & defaults =====
        // INFO: entry + input echo
        logger.info(lp("entry: fetching events; input days: {}"), daysInput.orElse(null));

        final ZoneId mountainTime = ZoneId.of("America/Denver");
        final int days = daysInput.orElse(14);
        if (daysInput.isEmpty()) {
            logger.info(lp("no 'days' provided; default applied: {} (America/Denver)"), days);
        } else {
            logger.info(lp("'days' provided by client: {} (America/Denver)"), days);
        }

        // ===== B. Input validation =====
        // Compute the start date (days ago) in Mountain Time and format as yyyy-MM-dd
        final ZonedDateTime daysAgoDate = ZonedDateTime.now(mountainTime).minusDays(days);
        final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        final String formattedDate = daysAgoDate.format(formatter);

        // Validation bounds (as per business rule)
        final LocalDate minDate = LocalDate.of(2022, 1, 1);                     // minimum starting ping for NASA EONET
        final LocalDate maxDate = LocalDate.now(mountainTime);                  // ending date is today's date
        final LocalDate candidate = daysAgoDate.toLocalDate();

        // DEBUG: show validation window and candidate
        logger.debug(lp("validation window: [{}..{}], candidate start: {}"), minDate, maxDate, candidate);

        if (candidate.isBefore(minDate) || candidate.isAfter(maxDate)) {
            logger.error(lp("input invalid: start {} outside bounds [{}..{}]"), formattedDate, minDate, maxDate);
            throw new GraphQLException("Date must be between 2022-01-01 and " + maxDate);
        }
        logger.info(lp("input valid: start={} within [{}..{}]"), formattedDate, minDate, maxDate);

        // ===== C. API request build =====
        // end date = now + 5 years (original logic preserved)
        final LocalDate dynamicEndDate = LocalDate.now(mountainTime).plusYears(5);
        final String formattedEndDate = dynamicEndDate.format(formatter);

        final String api_source = "https://eonet.gsfc.nasa.gov/api/v3/events?start="
                + formattedDate + "&end=" + formattedEndDate + "&status=all";

        logger.info(lp("fetch begin: GET {}"), api_source);

        // Measure end-to-end latency
        final Instant t0 = Instant.now();

        // ===== D. Execute request =====
        final String response;
        try {
            response = webClient
                    .get()
                    .uri(api_source)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception ex) {
            logger.error(lp("fetch failed: exception during HTTP call: {}"), ex.toString(), ex);
            throw new GraphQLException("API Fetch failed.");
        }

        final Duration httpLatency = Duration.between(t0, Instant.now());
        logger.info(lp("fetch complete: {} ms"), httpLatency.toMillis());

        if (response == null) {
            logger.error(lp("fetch returned null body"));
            throw new GraphQLException("API Fetch failed.");
        }
        logger.debug(lp("response length: {} bytes"), response.getBytes(StandardCharsets.UTF_8).length);
        logger.debug(lp("response preview: {}"), response.substring(0, Math.min(response.length(), 500)));

        // ===== E. Parse JSON =====
        final JsonObject root;
        try {
            root = gson.fromJson(response, JsonObject.class);
        } catch (Exception ex) {
            logger.error(lp("parse failed: invalid JSON: {}"), ex.toString(), ex);
            throw new GraphQLException("API Parse failed.");
        }

        if (root == null) {
            logger.error(lp("parse failed: root is null"));
            throw new GraphQLException("API Fetch failed.");
        }

        final JsonArray eventsArray = root.getAsJsonArray("events");
        if (eventsArray == null) {
            logger.warn(lp("no 'events' array present in response; returning empty list"));
            return List.of();
        }

        // Extract only the fields specified in the GraphQL schema
        final Type rawListType = new TypeToken<List<Map<String, Object>>>() {}.getType();
        final List<Map<String, Object>> rawEvents;
        try {
            rawEvents = gson.fromJson(eventsArray, rawListType);
        } catch (Exception ex) {
            logger.error(lp("parse failed: unable to map events array: {}"), ex.toString(), ex);
            throw new GraphQLException("API Parse failed.");
        }

        logger.info(lp("events array decoded: count={}"), (rawEvents != null ? rawEvents.size() : 0));
        if (rawEvents == null || rawEvents.isEmpty()) {
            logger.info(lp("no events after decoding; returning empty list"));
            return List.of();
        }

        // ===== F. Transform to GraphQL types =====
        final List<Event> finalEvents = new ArrayList<>(rawEvents.size());

        for (Map<String, Object> eventMap : rawEvents) {
            try {
                // Categories
                @SuppressWarnings("unchecked")
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
                @SuppressWarnings("unchecked")
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
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> geometryListRaw = (List<Map<String, Object>>) eventMap.get("geometry");
                List<Geometry> geometries = new ArrayList<>();
                if (geometryListRaw != null) {
                    for (Map<String, Object> geoMap : geometryListRaw) {
                        Float magnitudeValue = null;
                        Object val = geoMap.get("magnitudeValue");
                        if (val instanceof Number number) {
                            magnitudeValue = number.floatValue();
                        }

                        @SuppressWarnings("unchecked")
                        List<Double> coords = (List<Double>) geoMap.get("coordinates");

                        Geometry geometry = Geometry.newBuilder()
                                // keep existing behavior: Double in builder from Float source
                                .magnitudeValue(magnitudeValue != null ? magnitudeValue.doubleValue() : null)
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
            } catch (Exception ex) {
                // If a single event fails to map, log and continue (do not fail entire query)
                logger.warn(lp("event mapping skipped due to error: {} | data={}"), ex.toString(), eventMap, ex);
            }
        }

        logger.info(lp("transform complete: mapped events={}"), finalEvents.size());

        // ===== G. Return =====
        return finalEvents;
    }

}
