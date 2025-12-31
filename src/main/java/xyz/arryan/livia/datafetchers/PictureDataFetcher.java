package xyz.arryan.livia.datafetchers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.*;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.codegen.types.Explanation;
import xyz.arryan.livia.codegen.types.Picture;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import graphql.GraphQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.time.Instant;


@DgsComponent
public class PictureDataFetcher {

    private static final String COLLECTION = "pictures";

    private final WebClient webClient;
    private final Gson gson;
    private final MongoTemplate mongoTemplate;

    private static final Logger logger = LoggerFactory.getLogger(PictureDataFetcher.class);
    private static final String LOG_PREFIX = "API 3: PICTURE | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }

    @Autowired
    public PictureDataFetcher(Gson gson, WebClient webClient, MongoTemplate mongoTemplate) {
        this.webClient = webClient;
        this.gson = gson;
        this.mongoTemplate = mongoTemplate;
        logger.info(lp("constructor: initialized with MongoDB backend"));
    }

    @DgsQuery
    public Picture picture(@InputArgument String date) {
        logger.info(lp("entry: fetching picture; input date: {}"), date);
        final ZoneId mountainTime = ZoneId.of("America/Denver");
        LocalDate inputDate;
        if (date == null || date.isEmpty()) {
            date = String.valueOf(LocalDate.now(mountainTime));
            logger.info(lp("no date provided; default applied (America/Denver): {}"), date);
        }
        try {
            inputDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            logger.error(lp("validation failed: cannot parse date: {}"), date, e);
            throw new GraphQLException("Invalid date format. Use yyyy-MM-dd.");
        }
        LocalDate minDate = LocalDate.of(1995, 6, 16);
        LocalDate maxDate = LocalDate.now(mountainTime);
        if (inputDate.isBefore(minDate) || inputDate.isAfter(maxDate)) {
            logger.error(lp("validation failed: {} outside bounds [{}..{}]"), inputDate, minDate, maxDate);
            throw new GraphQLException("Date must be between 1995-06-16 and " + maxDate);
        }
        logger.info(lp("input valid: {} within [{}..{}]"), inputDate, minDate, maxDate);

        // Step 1: Check MongoDB for cached picture
        Picture cachedPicture = getFromMongoDB(date);
        if (cachedPicture != null) {
            logger.info(lp("MongoDB cache hit: found picture for {}"), date);
            return cachedPicture;
        }
        logger.info(lp("MongoDB cache miss: no picture for {}; will fetch and generate"), date);

        // Step 2: Fetch from Ellanan APOD API
        final String api_source = "https://apod.ellanan.com/api/?date=" + date;
        final String response = fetchWithRetry(api_source, 2);
        if (response == null) {
            throw new GraphQLException("Failed to fetch picture for " + date + " after multiple attempts. Please try again later.");
        }
        logger.debug(lp("response preview: {}"), response.substring(0, Math.min(response.length(), 400)));

        // Parse JSON
        final Type type = new TypeToken<Map<String, String>>(){}.getType();
        final Map<String, String> map;
        try {
            map = gson.fromJson(response, type);
        } catch (Exception ex) {
            logger.error(lp("parse failed: invalid JSON: {}"), ex.toString(), ex);
            throw new GraphQLException("Failed to parse picture data for " + date);
        }
        if (map == null) {
            logger.error(lp("parse failed: root map is null"));
            throw new GraphQLException("Failed to parse picture data for " + date);
        }

        // Choose media (prefer HD if available)
        final String media = map.get("hdurl") != null ? map.get("hdurl") : map.get("url");
        logger.info(lp("media selected: {}"), (map.get("hdurl") != null ? "hdurl" : "url"));

        String explanationOriginal = map.get("explanation");
        String explanationSummarized = explanationOriginal;
        String explanationKids = explanationOriginal;

        // Step 3: Generate AI explanations
        if (explanationOriginal != null && !explanationOriginal.isEmpty()) {
            try {
                logger.info(lp("generating summarized explanation via OpenAI"));
                explanationSummarized = generateSummary(clientBuilder(), explanationOriginal,
                    "Summarize the following explanation into 4–5 concise sentences. Only return the summary itself — no introduction or extra text. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
                logger.info(lp("summarized explanation generated"));
            } catch (Exception e) {
                logger.error(lp("failed to generate summarized: {}"), e.getMessage());
            }

            try {
                logger.info(lp("generating kids explanation via OpenAI"));
                explanationKids = generateSummary(clientBuilder(), explanationOriginal,
                    "Rewrite the following explanation in a fun, friendly, and simple way that a 7-year-old child can understand. Avoid complex words and explain anything tricky like you're telling a story to a curious kid. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
                logger.info(lp("kids explanation generated"));
            } catch (Exception e) {
                logger.error(lp("failed to generate kids: {}"), e.getMessage());
            }
        }

        // Step 4: Build Picture object
        Picture picture = Picture.newBuilder()
                .title(map.get("title"))
                .credit(map.get("credit"))
                .date(map.get("date"))
                .media(media)
                .copyright(map.get("copyright"))
                .media_type(map.get("media_type"))
                .build();

        // Step 5: Only save to MongoDB if OpenAI generation succeeded for both
        boolean summarizedSuccess = !explanationSummarized.equals(explanationOriginal);
        boolean kidsSuccess = !explanationKids.equals(explanationOriginal);

        if (summarizedSuccess && kidsSuccess) {
            saveToMongoDB(date, picture, explanationOriginal, explanationSummarized, explanationKids);
            logger.info(lp("saved picture to MongoDB for {}"), date);
        } else {
            logger.warn(lp("NOT caching - OpenAI generation failed (summarized={}, kids={})"), summarizedSuccess, kidsSuccess);
        }

        return picture;
    }

    @DgsData(parentType = "Picture", field = "explanation")
    public Explanation resolveExplanation(DgsDataFetchingEnvironment dfe) {
        logger.info(lp("explanation: resolving"));
        Picture picture = dfe.getSource();
        if (picture == null || picture.getDate() == null) {
            logger.error(lp("explanation: no picture in DFE source"));
            throw new GraphQLException("Picture does not exist.");
        }

        // Get explanations from MongoDB
        Map<String, String> explanations = getExplanationsFromMongoDB(picture.getDate());

        return Explanation.newBuilder()
                .original(explanations.getOrDefault("original", "Explanation not available"))
                .summarized(explanations.getOrDefault("summarized", "Explanation not available"))
                .kids(explanations.getOrDefault("kids", "Explanation not available"))
                .build();
    }

    private Picture getFromMongoDB(String date) {
        try {
            Document doc = mongoTemplate.findOne(
                Query.query(Criteria.where("date").is(date)),
                Document.class,
                COLLECTION
            );

            if (doc == null) {
                return null;
            }

            return Picture.newBuilder()
                    .date(doc.getString("date"))
                    .title(doc.getString("title"))
                    .credit(doc.getString("credit"))
                    .media(doc.getString("media"))
                    .copyright(doc.getString("copyright"))
                    .media_type(doc.getString("media_type"))
                    .build();
        } catch (Exception e) {
            logger.error(lp("MongoDB get failed: {}"), e.getMessage());
            return null;
        }
    }

    private Map<String, String> getExplanationsFromMongoDB(String date) {
        Map<String, String> result = new HashMap<>();
        try {
            Document doc = mongoTemplate.findOne(
                Query.query(Criteria.where("date").is(date)),
                Document.class,
                COLLECTION
            );

            if (doc != null) {
                result.put("original", doc.getString("explanation_original"));
                result.put("summarized", doc.getString("explanation_summarized"));
                result.put("kids", doc.getString("explanation_kids"));
            }
        } catch (Exception e) {
            logger.error(lp("MongoDB get explanations failed: {}"), e.getMessage());
        }
        return result;
    }

    private void saveToMongoDB(String date, Picture picture, String original, String summarized, String kids) {
        try {
            Document doc = new Document();
            doc.put("date", date);

            if (picture.getTitle() != null)
                doc.put("title", picture.getTitle());
            if (picture.getCredit() != null)
                doc.put("credit", picture.getCredit());
            if (picture.getMedia() != null)
                doc.put("media", picture.getMedia());
            if (picture.getCopyright() != null)
                doc.put("copyright", picture.getCopyright());
            if (picture.getMedia_type() != null)
                doc.put("media_type", picture.getMedia_type());
            if (original != null)
                doc.put("explanation_original", original);
            if (summarized != null)
                doc.put("explanation_summarized", summarized);
            if (kids != null)
                doc.put("explanation_kids", kids);

            // Upsert: update if exists, insert if not
            mongoTemplate.save(doc, COLLECTION);
        } catch (Exception e) {
            logger.error(lp("MongoDB save failed: {}"), e.getMessage());
        }
    }

    private OpenAIClientAsync clientBuilder() {
        return OpenAIOkHttpClientAsync.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();
    }

    private CompletableFuture<String> generateSummary(OpenAIClientAsync client, String explanation, String instruction) {
        final Instant t0 = Instant.now();
        String prompt = instruction + "\nText: " + explanation;
        ResponseCreateParams createParams = ResponseCreateParams.builder()
                .input(prompt)
                .model(ChatModel.GPT_4O)
                .build();
        CompletableFuture<String> future = new CompletableFuture<>();
        client.responses()
                .create(createParams)
                .thenAccept(summary -> {
                    StringBuilder output = new StringBuilder();
                    summary.output().stream()
                            .flatMap(item -> item.message().stream())
                            .flatMap(message -> message.content().stream())
                            .flatMap(content -> content.outputText().stream())
                            .forEach(outputText -> output.append(outputText.text()).append("\n"));
                    long ms = Duration.between(t0, Instant.now()).toMillis();
                    logger.info(lp("openai: response received in {} ms"), ms);
                    future.complete(output.toString().trim());
                })
                .exceptionally(ex -> {
                    logger.error(lp("openai: request failed: {}"), ex.toString(), ex);
                    future.completeExceptionally(ex);
                    return null;
                });
        return future;
    }

    private String fetchWithRetry(String url, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            logger.info(lp("fetch attempt {}/{}: GET {}"), attempt, maxAttempts, url);
            final Instant t0 = Instant.now();
            try {
                String response = webClient
                        .get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                final long httpMs = Duration.between(t0, Instant.now()).toMillis();
                if (response != null) {
                    logger.info(lp("fetch complete: {} ms (attempt {})"), httpMs, attempt);
                    return response;
                }
                logger.warn(lp("fetch returned null body (attempt {})"), attempt);
            } catch (Exception ex) {
                final long httpMs = Duration.between(t0, Instant.now()).toMillis();
                logger.error(lp("fetch failed after {} ms (attempt {}): {}"), httpMs, attempt, ex.toString());
            }
        }
        logger.error(lp("all {} fetch attempts failed for {}"), maxAttempts, url);
        return null;
    }
}
