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


@DgsComponent
public class PictureDataFetcher {

    private final Gson gson;
    private final MongoTemplate mongoTemplate;
    private static final Logger logger = LoggerFactory.getLogger(PictureDataFetcher.class);

    @Autowired
    public PictureDataFetcher(Gson gson, MongoTemplate mongoTemplate) {
        this.gson = gson;
        this.mongoTemplate = mongoTemplate;
    }

    @DgsQuery
    public Picture picture(@InputArgument String date) {
        logger.info("Fetching picture for date: {}", date);

        // A. Input Validation
        LocalDate inputDate;
        ZoneId mountainTime = ZoneId.of("America/Denver");
        if (date == null || date.isEmpty()) { // if date is empty set -> today's date (mountain time)
            date = String.valueOf(LocalDate.now(mountainTime));
            logger.info("Date was set to null, resolving America/Denver time zone, set date to: {}", date);
        }
        try { // YYYY-MM-DD format regardless of previous condition
            inputDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            logger.error("Date could not be parsed as a valid date: {}", date, e);
            throw new GraphQLException("Invalid date format. Use yyyy-MM-dd.");
        }
        LocalDate minDate = LocalDate.of(1995, 6, 16); // starting date of nasa apod
        LocalDate maxDate = LocalDate.now(mountainTime); // ending date is today's date, 2 hours after API should be updated.
        if (inputDate.isBefore(minDate) || inputDate.isAfter(maxDate)) {
            logger.error("Input date should be before max date or before min date {}", inputDate);
            throw new GraphQLException("Date must be between 1995-06-16 and " + maxDate);
        }

        // B. Database Check
        Document document = mongoTemplate.findOne(new Query(Criteria.where("date").is(date)), Document.class, "pictures");
        if (document != null) {
            logger.info("Found picture: {}", document.toJson());
            return Picture.newBuilder()
                    .title(document.getString("title"))
                    .credit(document.getString("credit"))
                    .date(document.getString("date"))
                    .media(document.getString("media"))
                    .copyright(document.getString("copyright"))
                    .media_type(document.getString("media_type"))
                    .build();
        }

        // C. Fetch fresh valid input from API
        String api_source = "https://apod.ellanan.com/api?date=" + date;
        WebClient webClient = WebClient.builder().build();
        String response = webClient
                .get()
                .uri(api_source)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        Type type = new TypeToken<Map<String, String>>(){}.getType();
        Map<String, String> map = gson.fromJson(response, type);
        if (map == null) {
            logger.error("Could not parse response from API");
            throw new GraphQLException("API Fetch failed.");
        }
        String media; // set media to higher quality if photo and hd exists
        if (map.get("hdurl") != null) {
            media = map.get("hdurl");
        } else {
            media = map.get("url");
        }

        // D. Cache response in database for others clients
        Document new_document = new Document();
        new_document.put("date", map.get("date"));
        new_document.put("title", map.get("title"));
        new_document.put("credit", map.get("credit"));
        new_document.put("media", media);
        new_document.put("explanation_original", map.get("explanation"));
        new_document.put("copyright", map.get("copyright"));
        new_document.put("media_type", map.get("media_type"));
        mongoTemplate.insert(new_document, "pictures");

        // F. Return JSON
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

        // A. Database Check
        Picture picture = dfe.getSource();
        logger.debug("Resolving explanation: {}", picture);
        if (picture == null) {
            logger.error("Picture could not be resolved");
            throw new GraphQLException("Picture does not exist.");
        }

        Document document;
        document = mongoTemplate.findOne(
                Query.query(Criteria.where("date").is(picture.getDate())),
                Document.class,
                "pictures"
        );
        if (document == null) {
            logger.error("Document could not be resolved");
            throw new GraphQLException("Document does not exist.");
        }

        if (document.containsKey("explanation_kids") && document.containsKey("explanation_summarized")) {
            logger.debug(String.valueOf(document));
            logger.info("Explanation kids and explanation summarized");
            return Explanation.newBuilder()
                    .original(document.getString("explanation_original"))
                    .kids(document.getString("explanation_kids"))
                    .summarized(document.getString("explanation_summarized"))
                    .build();
        }

        // B. Generate two different explanations using OpenAI
        OpenAIClientAsync client = OpenAIOkHttpClientAsync.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();
        String explanation_original = document.getString("explanation_original");
        String explanation_summarized = document.getString("explanation_summarized");
        if (explanation_summarized == null || explanation_summarized.isBlank()) {
            explanation_summarized = generateSummary(client, explanation_original, "Summarize the following explanation into 4–5 concise sentences. Only return the summary itself — no introduction or extra text. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
            document.put("explanation_summarized", explanation_summarized);
            mongoTemplate.save(document, "pictures");
            logger.info("Updated explanation summary");
        }

        String explanation_kids = document.getString("explanation_kids");
        if (explanation_kids == null || explanation_kids.isBlank()) {
            explanation_kids = generateSummary(client, explanation_original, "Rewrite the following explanation in a fun, friendly, and simple way that a 7-year-old child can understand. Avoid complex words and explain anything tricky like you’re telling a story to a curious kid. It should be 1 paragraph like the explanation. It will be used in an app to describe an image. Don't use hyphens in your response.").join();
            document.put("explanation_kids", explanation_kids);
            mongoTemplate.save(document, "pictures");
            logger.info("Updated explanation kids");
        }

        logger.info("Explanation kids and explanation summarized");
        return Explanation.newBuilder()
                .original(explanation_original)
                .kids(explanation_kids)
                .summarized(explanation_summarized)
                .build();

    }


    private static CompletableFuture<String> generateSummary(OpenAIClientAsync client, String explanation, String instruction) {
        String prompt = instruction + "\nText: " + explanation;
        ResponseCreateParams createParams = ResponseCreateParams.builder()
                .input(prompt)
                .model(ChatModel.GPT_4_1_MINI)
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

                    future.complete(output.toString().trim());
                })
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });
        logger.info("Generated summary");
        return future;
    }
}
