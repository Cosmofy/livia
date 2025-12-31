package xyz.arryan.livia.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class WeatherKitService {
    private static final Logger logger = LoggerFactory.getLogger(WeatherKitService.class);
    private static final String LOG_PREFIX = "WEATHERKIT | ";

    private final WebClient webClient;
    private final Gson gson;

    private static final String WEATHERKIT_URL = "https://weatherkit.apple.com/api/v1/weather";

    @Value("${WEATHERKIT_KEY_ID:}")
    private String keyId;

    @Value("${WEATHERKIT_TEAM_ID:}")
    private String teamId;

    @Value("${WEATHERKIT_SERVICE_ID:}")
    private String serviceId;

    @Value("${WEATHERKIT_PRIVATE_KEY:}")
    private String privateKeyPem;

    public WeatherKitService(WebClient webClient, Gson gson) {
        this.webClient = webClient;
        this.gson = gson;
    }

    public boolean isConfigured() {
        return keyId != null && !keyId.isEmpty()
                && teamId != null && !teamId.isEmpty()
                && serviceId != null && !serviceId.isEmpty()
                && privateKeyPem != null && !privateKeyPem.isEmpty();
    }

    private String generateToken() throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("WeatherKit credentials not configured");
        }

        // Parse the private key
        String keyContent = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        Instant now = Instant.now();

        return Jwts.builder()
                .header()
                    .add("kid", keyId)
                    .add("id", teamId + "." + serviceId)
                    .and()
                .issuer(teamId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .subject(serviceId)
                .signWith(privateKey)
                .compact();
    }

    /**
     * Fetch astronomy data (sunrise, sunset, moonrise, moonset, moonPhase) from WeatherKit.
     */
    public Map<String, Object> getAstronomy(double lat, double lon) {
        logger.info(LOG_PREFIX + "fetching astronomy for lat={}, lon={}", lat, lon);

        if (!isConfigured()) {
            logger.warn(LOG_PREFIX + "not configured, returning empty");
            return Map.of();
        }

        try {
            String token = generateToken();

            String url = String.format("%s/en/%.4f/%.4f", WEATHERKIT_URL, lat, lon);

            String response = webClient.get()
                    .uri(url + "?dataSets=forecastDaily")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                throw new RuntimeException("WeatherKit returned null");
            }

            JsonObject data = gson.fromJson(response, JsonObject.class);
            JsonObject daily = data.has("forecastDaily") ? data.getAsJsonObject("forecastDaily") : null;

            if (daily == null || !daily.has("days")) {
                logger.warn(LOG_PREFIX + "no forecastDaily data");
                return Map.of();
            }

            JsonArray days = daily.getAsJsonArray("days");
            if (days.isEmpty()) {
                return Map.of();
            }

            // Get today's data
            JsonObject today = days.get(0).getAsJsonObject();

            Map<String, Object> result = new HashMap<>();
            result.put("sunrise", getStringOrNull(today, "sunrise"));
            result.put("sunset", getStringOrNull(today, "sunset"));
            result.put("moonrise", getStringOrNull(today, "moonrise"));
            result.put("moonset", getStringOrNull(today, "moonset"));
            result.put("moonPhase", getStringOrNull(today, "moonPhase"));
            result.put("solarMidnight", getStringOrNull(today, "solarMidnight"));
            result.put("solarNoon", getStringOrNull(today, "solarNoon"));

            logger.info(LOG_PREFIX + "astronomy fetched successfully");
            return result;

        } catch (Exception e) {
            logger.error(LOG_PREFIX + "failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }
}
