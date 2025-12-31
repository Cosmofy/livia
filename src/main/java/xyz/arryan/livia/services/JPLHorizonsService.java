package xyz.arryan.livia.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JPLHorizonsService {
    private static final Logger logger = LoggerFactory.getLogger(JPLHorizonsService.class);
    private static final String LOG_PREFIX = "JPL | ";

    private static final String HORIZONS_URL = "https://ssd.jpl.nasa.gov/api/horizons.api";
    private static final double KM_TO_AU = 149597870.7;

    private final WebClient webClient;
    private final Gson gson;

    // JPL body IDs for planets
    private static final Map<String, String> PLANET_IDS = Map.of(
        "Mercury", "199",
        "Venus", "299",
        "Earth", "399",
        "Mars", "499",
        "Jupiter", "599",
        "Saturn", "699",
        "Uranus", "799",
        "Neptune", "899"
    );

    public JPLHorizonsService(WebClient webClient, Gson gson) {
        this.webClient = webClient;
        this.gson = gson;
    }

    /**
     * Fetch current heliocentric position for a planet.
     * Returns map with positionX, positionY, positionZ (in AU) and distanceFromSun (in AU).
     */
    public Map<String, Double> getPlanetPosition(String planetName) {
        String bodyId = PLANET_IDS.get(planetName);
        if (bodyId == null) {
            logger.warn(LOG_PREFIX + "Unknown planet: {}", planetName);
            return null;
        }

        logger.info(LOG_PREFIX + "Fetching position for {} (body ID: {})", planetName, bodyId);

        try {
            LocalDate today = LocalDate.now();
            LocalDate tomorrow = today.plusDays(1);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            // Build URL with proper encoding - using quotes around values as JPL expects
            String url = String.format(
                "%s?format=json&COMMAND='%s'&EPHEM_TYPE=VECTORS&CENTER='500@10'&START_TIME='%s'&STOP_TIME='%s'&STEP_SIZE='1d'",
                HORIZONS_URL,
                bodyId,
                today.format(formatter),
                tomorrow.format(formatter)
            );

            logger.debug(LOG_PREFIX + "Request URL: {}", url);

            String response = webClient.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            if (response == null) {
                throw new RuntimeException("JPL Horizons returned null");
            }

            JsonObject data = gson.fromJson(response, JsonObject.class);
            
            // Check for error response
            if (data.has("error")) {
                logger.error(LOG_PREFIX + "API error: {}", data.get("error").getAsString());
                return null;
            }
            
            if (!data.has("result")) {
                logger.error(LOG_PREFIX + "No result field in response");
                return null;
            }

            String result = data.get("result").getAsString();
            return parseVectorData(result);

        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to fetch position for {}: {}", planetName, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the JPL Horizons vector data to extract X, Y, Z positions.
     */
    private Map<String, Double> parseVectorData(String result) {
        // Look for the data section between 13598SOE and 13598EOE
        int soeIndex = result.indexOf("$$SOE");
        int eoeIndex = result.indexOf("$$EOE");
        
        if (soeIndex == -1 || eoeIndex == -1) {
            logger.error(LOG_PREFIX + "Could not find data markers in response");
            logger.debug(LOG_PREFIX + "Response snippet: {}", result.substring(0, Math.min(500, result.length())));
            return null;
        }

        String dataSection = result.substring(soeIndex, eoeIndex);
        logger.debug(LOG_PREFIX + "Data section: {}", dataSection);

        // Pattern to match: X = 1.234E+08 or X =-1.234E+08 (note: space before = but may not have space after)
        Pattern xPattern = Pattern.compile("X\\s*=\\s*([\\-+]?[\\d.]+E[\\-+]?\\d+)");
        Pattern yPattern = Pattern.compile("Y\\s*=\\s*([\\-+]?[\\d.]+E[\\-+]?\\d+)");
        Pattern zPattern = Pattern.compile("Z\\s*=\\s*([\\-+]?[\\d.]+E[\\-+]?\\d+)");

        Matcher xMatcher = xPattern.matcher(dataSection);
        Matcher yMatcher = yPattern.matcher(dataSection);
        Matcher zMatcher = zPattern.matcher(dataSection);

        if (!xMatcher.find() || !yMatcher.find() || !zMatcher.find()) {
            logger.error(LOG_PREFIX + "Could not parse X, Y, Z from data section");
            return null;
        }

        // Values are in km, convert to AU
        double xKm = Double.parseDouble(xMatcher.group(1));
        double yKm = Double.parseDouble(yMatcher.group(1));
        double zKm = Double.parseDouble(zMatcher.group(1));

        double xAU = xKm / KM_TO_AU;
        double yAU = yKm / KM_TO_AU;
        double zAU = zKm / KM_TO_AU;
        double distanceAU = Math.sqrt(xAU * xAU + yAU * yAU + zAU * zAU);

        logger.info(LOG_PREFIX + "Parsed position: X={} AU, Y={} AU, Z={} AU, dist={} AU", 
            String.format("%.4f", xAU), 
            String.format("%.4f", yAU), 
            String.format("%.4f", zAU),
            String.format("%.4f", distanceAU));

        Map<String, Double> position = new HashMap<>();
        position.put("positionX", xAU);
        position.put("positionY", yAU);
        position.put("positionZ", zAU);
        position.put("distanceFromSun", distanceAU);

        return position;
    }
}
