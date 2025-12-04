package xyz.arryan.livia.datafetchers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.*;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.codegen.types.Explanation;
import xyz.arryan.livia.codegen.types.Picture;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;
import graphql.GraphQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;


@DgsComponent
public class PictureDataFetcher {

    private final WebClient webClient;
    private final Gson gson;
    private final MongoTemplate mongoTemplate;
    private static final Logger logger = LoggerFactory.getLogger(PictureDataFetcher.class);
    private static final String LOG_PREFIX = "API 3: PICTURE | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }

    @Autowired
    public PictureDataFetcher(Gson gson, MongoTemplate mongoTemplate, WebClient webClient) {
        this.webClient = webClient;
        this.gson = gson;
        this.mongoTemplate = mongoTemplate;
    }

    @DgsQuery
    public Picture picture(@InputArgument String date) {
        // ===== A. Input Logging & Defaults =====
        logger.info(lp("entry: fetching picture; input date: {}"), date);
        final ZoneId mountainTime = ZoneId.of("America/Denver");
        LocalDate inputDate;
        if (date == null || date.isEmpty()) { // if date is empty set -> today's date (mountain time)
            date = String.valueOf(LocalDate.now(mountainTime));
            logger.info(lp("no date provided; default applied (America/Denver): {}"), date);
        }
        try { // YYYY-MM-DD format regardless of previous condition
            inputDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            logger.error(lp("validation failed: cannot parse date: {}"), date, e);
            throw new GraphQLException("Invalid date format. Use yyyy-MM-dd.");
        }
        LocalDate minDate = LocalDate.of(1995, 6, 16); // starting date of nasa apod
        LocalDate maxDate = LocalDate.now(mountainTime); // ending date is today's date, 2 hours after API should be updated.
        if (inputDate.isBefore(minDate) || inputDate.isAfter(maxDate)) {
            logger.error(lp("validation failed: {} outside bounds [{}..{}]"), inputDate, minDate, maxDate);
            throw new GraphQLException("Date must be between 1995-06-16 and " + maxDate);
        }
        logger.info(lp("input valid: {} within [{}..{}]"), inputDate, minDate, maxDate);

        // ===== B. Database Check =====
        Document document = mongoTemplate.findOne(new Query(Criteria.where("date").is(date)), Document.class, "pictures");
        if (document != null) {
            logger.info(lp("cache hit: found picture for {}"), date);
            return Picture.newBuilder()
                    .title(document.getString("title"))
                    .credit(document.getString("credit"))
                    .date(document.getString("date"))
                    .media(document.getString("media"))
                    .copyright(document.getString("copyright"))
                    .media_type(document.getString("media_type"))
                    .build();
        }
        logger.info(lp("cache miss: no picture for {}; will fetch"), date);

        // ===== C. Fetch from API =====
        final String api_source = "https://apod.ellanan.com/api?date=" + date;
        logger.info(lp("fetch begin: GET {}"), api_source);
        final Instant t0 = Instant.now();
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
            return getFallbackPicture(date);
        }
        final long httpMs = Duration.between(t0, Instant.now()).toMillis();
        logger.info(lp("fetch complete: {} ms"), httpMs);

        if (response == null) {
            logger.error(lp("fetch returned null body"));
            return getFallbackPicture(date);
        }
        logger.debug(lp("response preview: {}"), response.substring(0, Math.min(response.length(), 400)));

        // ===== D. Parse JSON =====
        final Type type = new TypeToken<Map<String, String>>(){}.getType();
        final Map<String, String> map;
        try {
            map = gson.fromJson(response, type);
        } catch (Exception ex) {
            logger.error(lp("parse failed: invalid JSON: {}"), ex.toString(), ex);
            return getFallbackPicture(date);
        }
        if (map == null) {
            logger.error(lp("parse failed: root map is null"));
            return getFallbackPicture(date);
        }

        // ===== E. Choose media (prefer HD if available) =====
        final String media;
        if (map.get("hdurl") != null) {
            media = map.get("hdurl");
        } else {
            media = map.get("url");
        }
        logger.info(lp("media selected: {}"), (map.get("hdurl") != null ? "hdurl" : "url"));

        // ===== F. Cache response in database =====
        try {
            Document new_document = new Document();
            new_document.put("date", map.get("date"));
            new_document.put("title", map.get("title"));
            new_document.put("credit", map.get("credit"));
            new_document.put("media", media);
            new_document.put("explanation_original", map.get("explanation"));
            new_document.put("copyright", map.get("copyright"));
            new_document.put("media_type", map.get("media_type"));
            mongoTemplate.insert(new_document, "pictures");
            logger.info(lp("cache write: stored picture for {}"), map.get("date"));
        } catch (Exception ex) {
            // Non-fatal for serving the response; log as error but still return the picture
            logger.error(lp("cache write failed: {}"), ex.toString(), ex);
        }

        // ===== G. Return DTO =====
        logger.info(lp("returning picture for {}"), map.get("date"));
        return Picture.newBuilder()
                .title(map.get("title"))
                .credit(map.get("credit"))
                .date(map.get("date"))
                .media(media)
                .copyright(map.get("copyright"))
                .media_type(map.get("media_type"))
                .build();
    }

    @DgsData(parentType = "Picture", field = "explanation")
    public Explanation resolveExplanation(DgsDataFetchingEnvironment dfe) {
        // ===== A. Entry & Source Validation =====
        logger.info(lp("explanation: entry"));
        Picture picture = dfe.getSource();
        logger.debug(lp("explanation: source picture: {}"), picture);
        if (picture == null) {
            logger.error(lp("explanation: no picture in DFE source"));
            throw new GraphQLException("Picture does not exist.");
        }

        // ===== B. Document Lookup =====
        Document document = mongoTemplate.findOne(
                Query.query(Criteria.where("date").is(picture.getDate())),
                Document.class,
                "pictures"
        );
        if (document == null) {
            logger.error(lp("explanation: document not found for date={}"), picture.getDate());
            throw new GraphQLException("Document does not exist.");
        }

        // If both explanations already exist, return immediately
        if (document.containsKey("explanation_kids") && document.containsKey("explanation_summarized")) {
            logger.info(lp("explanation: cache hit for date={} (kids & summarized present)"), picture.getDate());
            return Explanation.newBuilder()
                    .original(document.getString("explanation_original"))
                    .kids(document.getString("explanation_kids"))
                    .summarized(document.getString("explanation_summarized"))
                    .build();
        }

        // ===== C. Generate missing explanations via OpenAI =====
        final String explanation_original = document.getString("explanation_original");
        String explanation_summarized = document.getString("explanation_summarized");
        String explanation_kids = document.getString("explanation_kids");

        if (explanation_summarized == null || explanation_summarized.isBlank()) {
            logger.info(lp("explanation: generating summarized text (date={})"), picture.getDate());
            final Instant t0 = Instant.now();
            explanation_summarized = generateSummary(clientBuilder(), explanation_original, "Summarize the following explanation into 4–5 concise sentences. Only return the summary itself — no introduction or extra text. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
            logger.info(lp("explanation: summarized generated in {} ms"), Duration.between(t0, Instant.now()).toMillis());
            document.put("explanation_summarized", explanation_summarized);
            mongoTemplate.save(document, "pictures");
            logger.info(lp("explanation: updated summarized in DB (date={})"), picture.getDate());
        }

        if (explanation_kids == null || explanation_kids.isBlank()) {
            logger.info(lp("explanation: generating kids text (date={})"), picture.getDate());
            final Instant t0 = Instant.now();
            explanation_kids = generateSummary(clientBuilder(), explanation_original, "Rewrite the following explanation in a fun, friendly, and simple way that a 7-year-old child can understand. Avoid complex words and explain anything tricky like you’re telling a story to a curious kid. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
            logger.info(lp("explanation: kids generated in {} ms"), Duration.between(t0, Instant.now()).toMillis());
            document.put("explanation_kids", explanation_kids);
            mongoTemplate.save(document, "pictures");
            logger.info(lp("explanation: updated kids in DB (date={})"), picture.getDate());
        }

        logger.info(lp("explanation: returning explanations (date={})"), picture.getDate());
        return Explanation.newBuilder()
                .original(explanation_original)
                .kids(explanation_kids)
                .summarized(explanation_summarized)
                .build();
    }

    private OpenAIClientAsync clientBuilder() {
        return OpenAIOkHttpClientAsync.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();
    }


    private static CompletableFuture<String> generateSummary(OpenAIClientAsync client, String explanation, String instruction) {
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

    private Picture getFallbackPicture(String requestedDate) {
        logger.warn(lp("fallback: API failed, attempting to return cached picture"));

        // Try to find the most recent picture in the database
        try {
            Document latestDoc = mongoTemplate.findOne(
                new Query().with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "date")).limit(1),
                Document.class,
                "pictures"
            );

            if (latestDoc != null) {
                logger.info(lp("fallback: returning cached picture from {}"), latestDoc.getString("date"));
                return Picture.newBuilder()
                        .title(latestDoc.getString("title"))
                        .credit(latestDoc.getString("credit"))
                        .date(latestDoc.getString("date"))
                        .media(latestDoc.getString("media"))
                        .copyright(latestDoc.getString("copyright"))
                        .media_type(latestDoc.getString("media_type"))
                        .build();
            }
        } catch (Exception e) {
            logger.error(lp("fallback: failed to query MongoDB: {}"), e.toString(), e);
        }

        // Ultimate fallback: return null (GraphQL will handle gracefully)
        logger.warn(lp("fallback: no cached pictures available, returning null"));
        return null;
    }
}
