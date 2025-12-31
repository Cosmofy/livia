package xyz.arryan.livia.datafetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@DgsComponent
public class ApiKeyDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyDataFetcher.class);

    private final Set<String> acceptedPassphrases;
    private final String litellmMasterKey;
    private final String litellmBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ApiKeyDataFetcher() {
        // Load accepted passphrases from env (comma-separated)
        String passphrases = System.getenv("ACCEPTED_PASSPHRASES");
        if (passphrases != null && !passphrases.isBlank()) {
            this.acceptedPassphrases = Set.of(passphrases.split(","));
        } else {
            this.acceptedPassphrases = Set.of();
            logger.warn("API KEY | No ACCEPTED_PASSPHRASES configured");
        }

        // Load LiteLLM master key from env
        this.litellmMasterKey = System.getenv("LITELLM_MASTER_KEY");
        if (this.litellmMasterKey == null || this.litellmMasterKey.isBlank()) {
            logger.warn("API KEY | No LITELLM_MASTER_KEY configured");
        }

        // LiteLLM base URL from env (required)
        this.litellmBaseUrl = System.getenv("LITELLM_BASE_URL");
        if (this.litellmBaseUrl == null || this.litellmBaseUrl.isBlank()) {
            logger.warn("API KEY | No LITELLM_BASE_URL configured");
        }

        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();

        logger.info("API KEY | Initialized with {} accepted passphrases, LiteLLM URL: {}",
                    acceptedPassphrases.size(), litellmBaseUrl);
    }

    @DgsQuery
    public String apiKey(@InputArgument String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            logger.debug("API KEY | Empty passphrase provided");
            return null;
        }

        if (!acceptedPassphrases.contains(passphrase)) {
            logger.debug("API KEY | Invalid passphrase rejected");
            return null;
        }

        logger.info("API KEY | Valid passphrase accepted, generating virtual key");
        return generateVirtualKey();
    }

    private String generateVirtualKey() {
        try {
            // Calculate tomorrow's date for expiry
            String expiryDate = LocalDate.now().plusDays(1)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);

            String requestBody = """
                {
                    "models": ["gpt-4o"],
                    "max_budget": 0.40,
                    "duration": "24h",
                    "metadata": {
                        "source": "livia-graphql"
                    }
                }
                """;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(litellmBaseUrl + "/key/generate"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + litellmMasterKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String virtualKey = json.get("key").asText();
                logger.info("API KEY | Virtual key generated successfully");
                return virtualKey;
            } else {
                logger.error("API KEY | Failed to generate virtual key: {} - {}",
                            response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            logger.error("API KEY | Error generating virtual key", e);
            return null;
        }
    }
}
