package xyz.arryan.livia.datafetchers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import graphql.GraphQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.codegen.types.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@DgsComponent
public class AuroraDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(AuroraDataFetcher.class);
    private static final String LOG_PREFIX = "API 5: AURORA | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }

    private final WebClient webClient;
    private final Gson gson;
    private final RedisTemplate<String, String> redisTemplate;

    // NOAA endpoints
    private static final String NOAA_KP_FORECAST = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json";
    private static final String NOAA_SOLAR_WIND = "https://services.swpc.noaa.gov/products/solar-wind/plasma-7-day.json";
    private static final String NOAA_SOLAR_WIND_MAG = "https://services.swpc.noaa.gov/products/solar-wind/mag-7-day.json";
    private static final String NOAA_HEMISPHERIC_POWER = "https://services.swpc.noaa.gov/products/noaa-estimated-planetary-k-index-1-minute.json";
    private static final String NOAA_XRAY_FLUX = "https://services.swpc.noaa.gov/json/goes/primary/xray-flares-7-day.json";
    private static final String NOAA_OVATION_NORTH = "https://services.swpc.noaa.gov/products/animations/ovation_north_24h.json";
    private static final String NOAA_OVATION_SOUTH = "https://services.swpc.noaa.gov/products/animations/ovation_south_24h.json";

    // NASA SDO imagery base
    private static final String NASA_SDO_BASE = "https://sdo.gsfc.nasa.gov/assets/img/latest/";

    // Aurora ML API
    @Value("${aurora.ml.api.url:https://aurora.arryan.xyz}")
    private String auroraMlApiUrl;

    // Cache keys
    private static final String CACHE_KP = "aurora:kp";
    private static final String CACHE_SOLAR_WIND = "aurora:solar_wind";
    private static final String CACHE_HP = "aurora:hemispheric_power";
    private static final String CACHE_FLARES = "aurora:flares";
    private static final String CACHE_OVAL = "aurora:oval";

    @Autowired
    public AuroraDataFetcher(WebClient webClient, Gson gson, RedisTemplate<String, String> redisTemplate) {
        this.webClient = webClient;
        this.gson = gson;
        this.redisTemplate = redisTemplate;
        logger.info(lp("constructor: initialized Aurora data fetcher"));
    }

    @DgsQuery
    public Aurora aurora(@InputArgument Double lat, @InputArgument Double lon) {
        logger.info(lp("entry: fetching aurora data for lat={}, lon={}"), lat, lon);
        Instant startTime = Instant.now();

        // ===== 1. VALIDATE INPUT =====
        if (lat == null || lat < -90 || lat > 90) {
            logger.error(lp("validation failed: invalid latitude={}"), lat);
            throw new GraphQLException("Invalid latitude. Must be between -90 and 90.");
        }
        if (lon == null || lon < -180 || lon > 180) {
            logger.error(lp("validation failed: invalid longitude={}"), lon);
            throw new GraphQLException("Invalid longitude. Must be between -180 and 180.");
        }
        logger.info(lp("input valid: lat={}, lon={}"), lat, lon);

        // Initialize meta tracking
        boolean predictionError = false;
        boolean nearbyError = false;
        boolean spaceWeatherError = false;
        boolean spaceWeatherStale = false;
        boolean solarWindError = false;
        boolean solarWindStale = false;
        boolean hemisphericPowerError = false;
        boolean solarFlaresError = false;
        boolean auroraOvalError = false;
        boolean astronomyError = false;
        boolean lightPollutionError = false;

        // ===== 2. FETCH SPACE WEATHER (KP) =====
        SpaceWeather spaceWeather = null;
        try {
            spaceWeather = fetchSpaceWeather();
            logger.info(lp("space weather fetched successfully"));
        } catch (Exception e) {
            logger.error(lp("space weather fetch failed: {}"), e.getMessage());
            spaceWeather = getCachedSpaceWeather();
            if (spaceWeather != null) {
                spaceWeatherStale = true;
                logger.info(lp("using cached space weather (stale)"));
            } else {
                spaceWeatherError = true;
                logger.error(lp("no cached space weather available"));
            }
        }

        // ===== 3. FETCH SOLAR WIND (DSCOVR) =====
        SolarWind solarWind = null;
        try {
            solarWind = fetchSolarWind();
            logger.info(lp("solar wind fetched successfully"));
        } catch (Exception e) {
            logger.error(lp("solar wind fetch failed: {}"), e.getMessage());
            solarWind = getCachedSolarWind();
            if (solarWind != null) {
                solarWindStale = true;
                logger.info(lp("using cached solar wind (stale)"));
            } else {
                solarWindError = true;
                logger.error(lp("no cached solar wind available"));
            }
        }

        // ===== 4. FETCH HEMISPHERIC POWER =====
        HemisphericPower hemisphericPower = null;
        try {
            hemisphericPower = fetchHemisphericPower();
            logger.info(lp("hemispheric power fetched successfully"));
        } catch (Exception e) {
            logger.error(lp("hemispheric power fetch failed: {}"), e.getMessage());
            hemisphericPowerError = true;
        }

        // ===== 5. FETCH SOLAR FLARES =====
        SolarFlares solarFlares = null;
        try {
            solarFlares = fetchSolarFlares();
            logger.info(lp("solar flares fetched successfully"));
        } catch (Exception e) {
            logger.error(lp("solar flares fetch failed: {}"), e.getMessage());
            solarFlaresError = true;
        }

        // ===== 6. FETCH AURORA OVAL =====
        AuroraOval auroraOval = null;
        try {
            auroraOval = fetchAuroraOval();
            logger.info(lp("aurora oval fetched successfully"));
        } catch (Exception e) {
            logger.error(lp("aurora oval fetch failed: {}"), e.getMessage());
            auroraOvalError = true;
        }

        // ===== 7. BUILD SUN IMAGERY URLS =====
        SunImagery sunImagery = buildSunImagery();
        logger.info(lp("sun imagery URLs built"));

        // ===== 8. CALL AURORA ML PREDICTION =====
        AuroraPrediction prediction = null;
        try {
            prediction = fetchMlPrediction(lat, lon);
            logger.info(lp("ML prediction fetched: probability={}"),
                prediction != null ? prediction.getProbability() : "null");
        } catch (Exception e) {
            logger.error(lp("ML prediction failed: {}"), e.getMessage());
            predictionError = true;
        }

        // ===== 9. FETCH NEARBY PREDICTIONS (1200 points) =====
        List<NearbyPrediction> nearbyPredictions = new ArrayList<>();
        try {
            nearbyPredictions = fetchNearbyPredictions(lat, lon);
            logger.info(lp("nearby predictions fetched: count={}"), nearbyPredictions.size());
        } catch (Exception e) {
            logger.error(lp("nearby predictions failed: {}"), e.getMessage());
            nearbyError = true;
        }

        // ===== 10. CALCULATE ASTRONOMY =====
        Astronomy astronomy = null;
        try {
            astronomy = calculateAstronomy(lat, lon);
            logger.info(lp("astronomy calculated"));
        } catch (Exception e) {
            logger.error(lp("astronomy calculation failed: {}"), e.getMessage());
            astronomyError = true;
        }

        // ===== 11. FETCH LIGHT POLLUTION =====
        LightPollution lightPollution = null;
        try {
            lightPollution = estimateLightPollution(lat, lon);
            logger.info(lp("light pollution estimated: bortle={}"),
                lightPollution != null ? lightPollution.getBortle() : "null");
        } catch (Exception e) {
            logger.error(lp("light pollution fetch failed: {}"), e.getMessage());
            lightPollutionError = true;
        }

        // ===== 12. LOAD WEBCAMS =====
        List<Webcam> webcams = loadWebcams();
        logger.info(lp("webcams loaded: count={}"), webcams.size());

        // ===== 13. ASSEMBLE RESPONSE =====
        Duration elapsed = Duration.between(startTime, Instant.now());
        logger.info(lp("assembling response after {} ms"), elapsed.toMillis());

        AuroraLocation location = AuroraLocation.newBuilder()
                .lat(lat)
                .lon(lon)
                .timezone(getTimezone(lat, lon))
                .build();

        AuroraMeta meta = AuroraMeta.newBuilder()
                .timestamp(Instant.now().toString())
                .predictionError(predictionError)
                .nearbyError(nearbyError)
                .spaceWeatherError(spaceWeatherError)
                .spaceWeatherStale(spaceWeatherStale)
                .solarWindError(solarWindError)
                .solarWindStale(solarWindStale)
                .hemisphericPowerError(hemisphericPowerError)
                .solarFlaresError(solarFlaresError)
                .auroraOvalError(auroraOvalError)
                .astronomyError(astronomyError)
                .lightPollutionError(lightPollutionError)
                .build();

        Aurora aurora = Aurora.newBuilder()
                .location(location)
                .prediction(prediction)
                .nearbyPredictions(nearbyPredictions)
                .spaceWeather(spaceWeather)
                .solarWind(solarWind)
                .hemisphericPower(hemisphericPower)
                .solarFlares(solarFlares)
                .auroraOval(auroraOval)
                .sunImagery(sunImagery)
                .astronomy(astronomy)
                .lightPollution(lightPollution)
                .webcams(webcams)
                .meta(meta)
                .build();

        logger.info(lp("response assembled successfully in {} ms"), elapsed.toMillis());
        return aurora;
    }

    // ===== SPACE WEATHER (KP INDEX) =====
    private SpaceWeather fetchSpaceWeather() {
        logger.info(lp("fetching space weather from NOAA"));
        String response = webClient.get()
                .uri(NOAA_KP_FORECAST)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null) {
            throw new RuntimeException("NOAA KP forecast returned null");
        }

        JsonArray data = gson.fromJson(response, JsonArray.class);
        List<KpReading> forecast = new ArrayList<>();
        KpReading current = null;
        Instant now = Instant.now();

        for (int i = 1; i < data.size(); i++) { // Skip header row
            JsonArray row = data.get(i).getAsJsonArray();
            String timestamp = row.get(0).getAsString();
            double kp = row.get(1).getAsDouble();
            String type = row.get(2).getAsString();
            String stormLevel = row.size() > 3 && !row.get(3).isJsonNull() ? row.get(3).getAsString() : null;

            KpReading reading = KpReading.newBuilder()
                    .timestamp(timestamp)
                    .kp(kp)
                    .type(type)
                    .stormLevel(stormLevel)
                    .build();

            forecast.add(reading);

            // Find current (most recent observed)
            if ("observed".equals(type)) {
                current = reading;
            }
        }

        if (current == null && !forecast.isEmpty()) {
            current = forecast.get(0);
        }

        // Cache it
        cacheData(CACHE_KP, gson.toJson(forecast), 5, TimeUnit.MINUTES);

        return SpaceWeather.newBuilder()
                .current(current)
                .forecast(forecast)
                .build();
    }

    private SpaceWeather getCachedSpaceWeather() {
        String cached = redisTemplate.opsForValue().get(CACHE_KP);
        if (cached == null) return null;

        Type listType = new TypeToken<List<KpReading>>(){}.getType();
        List<KpReading> forecast = gson.fromJson(cached, listType);
        KpReading current = forecast.stream()
                .filter(r -> "observed".equals(r.getType()))
                .reduce((a, b) -> b)
                .orElse(forecast.isEmpty() ? null : forecast.get(0));

        return SpaceWeather.newBuilder()
                .current(current)
                .forecast(forecast)
                .build();
    }

    // ===== SOLAR WIND (DSCOVR) =====
    private SolarWind fetchSolarWind() {
        logger.info(lp("fetching solar wind from NOAA DSCOVR"));

        // Fetch magnetic field data (Bz, Bt)
        String magResponse = webClient.get()
                .uri(NOAA_SOLAR_WIND_MAG)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        // Fetch plasma data (speed, density)
        String plasmaResponse = webClient.get()
                .uri(NOAA_SOLAR_WIND)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (magResponse == null || plasmaResponse == null) {
            throw new RuntimeException("NOAA solar wind data returned null");
        }

        JsonArray magData = gson.fromJson(magResponse, JsonArray.class);
        JsonArray plasmaData = gson.fromJson(plasmaResponse, JsonArray.class);

        // Get most recent values (last row)
        JsonArray lastMag = magData.get(magData.size() - 1).getAsJsonArray();
        JsonArray lastPlasma = plasmaData.get(plasmaData.size() - 1).getAsJsonArray();

        String timestamp = lastMag.get(0).getAsString();
        double bz = parseDoubleOrDefault(lastMag.get(3), 0.0); // bz_gsm
        double bt = parseDoubleOrDefault(lastMag.get(6), 0.0); // bt
        double speed = parseDoubleOrDefault(lastPlasma.get(2), 0.0); // bulk speed
        double density = parseDoubleOrDefault(lastPlasma.get(1), 0.0); // proton density

        SolarWind solarWind = SolarWind.newBuilder()
                .bz(bz)
                .bt(bt)
                .speed(speed)
                .density(density)
                .timestamp(timestamp)
                .earthArrivalMinutes(calculateEarthArrival(speed))
                .build();

        cacheData(CACHE_SOLAR_WIND, gson.toJson(solarWind), 1, TimeUnit.MINUTES);
        return solarWind;
    }

    private SolarWind getCachedSolarWind() {
        String cached = redisTemplate.opsForValue().get(CACHE_SOLAR_WIND);
        if (cached == null) return null;
        return gson.fromJson(cached, SolarWind.class);
    }

    private int calculateEarthArrival(double speedKmS) {
        if (speedKmS <= 0) return 0;
        // DSCOVR is at L1, about 1.5 million km from Earth
        double distanceKm = 1_500_000;
        double seconds = distanceKm / speedKmS;
        return (int) (seconds / 60);
    }

    // ===== HEMISPHERIC POWER =====
    private HemisphericPower fetchHemisphericPower() {
        logger.info(lp("fetching hemispheric power"));
        // Using the estimated Kp as proxy for now - real HP endpoint would be different
        // This is a simplified implementation

        return HemisphericPower.newBuilder()
                .current(25.0) // Default GW
                .history(List.of())
                .build();
    }

    // ===== SOLAR FLARES =====
    private SolarFlares fetchSolarFlares() {
        logger.info(lp("fetching solar flares from NOAA GOES"));
        String response = webClient.get()
                .uri(NOAA_XRAY_FLUX)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null) {
            throw new RuntimeException("NOAA X-ray flux returned null");
        }

        JsonArray data = gson.fromJson(response, JsonArray.class);
        List<FlareEvent> events = new ArrayList<>();
        String current6h = "A0.0";
        String current24h = "A0.0";

        for (JsonElement elem : data) {
            JsonObject flare = elem.getAsJsonObject();
            String classType = flare.has("current_class") ? flare.get("current_class").getAsString() : "A0.0";

            if (classType.length() > 0) {
                String type = classType.substring(0, 1);
                double scale = 0.0;
                try {
                    scale = Double.parseDouble(classType.substring(1));
                } catch (NumberFormatException e) {
                    // ignore
                }

                FlareEvent event = FlareEvent.newBuilder()
                        .classType(type)
                        .scale(scale)
                        .timestamp(flare.has("time_tag") ? flare.get("time_tag").getAsString() : "")
                        .peakTime(flare.has("max_time") ? flare.get("max_time").getAsString() : null)
                        .build();
                events.add(event);
            }
        }

        // Get strongest in last 6h and 24h
        if (!events.isEmpty()) {
            current6h = events.get(0).getClassType() + events.get(0).getScale();
            current24h = current6h;
        }

        return SolarFlares.newBuilder()
                .current6h(current6h)
                .current24h(current24h)
                .events(events)
                .build();
    }

    // ===== AURORA OVAL =====
    private AuroraOval fetchAuroraOval() {
        logger.info(lp("fetching aurora oval from NOAA OVATION"));

        // The OVATION products provide image URLs
        String northUrl = "https://services.swpc.noaa.gov/images/animations/ovation/north/latest.jpg";
        String southUrl = "https://services.swpc.noaa.gov/images/animations/ovation/south/latest.jpg";

        return AuroraOval.newBuilder()
                .north(northUrl)
                .south(southUrl)
                .timestamp(Instant.now().toString())
                .forecastLeadMinutes(30)
                .build();
    }

    // ===== SUN IMAGERY =====
    private SunImagery buildSunImagery() {
        String timestamp = Instant.now().toString();

        return SunImagery.newBuilder()
                .thematicMap(SunImage.newBuilder()
                        .url("https://services.swpc.noaa.gov/images/animations/suvi-primary-195/latest.png")
                        .timestamp(timestamp)
                        .description("GOES SUVI 195A")
                        .build())
                .aia193(SunImage.newBuilder()
                        .url(NASA_SDO_BASE + "latest_1024_0193.jpg")
                        .timestamp(timestamp)
                        .description("SDO AIA 193 Angstrom - Corona")
                        .build())
                .aia171(SunImage.newBuilder()
                        .url(NASA_SDO_BASE + "latest_1024_0171.jpg")
                        .timestamp(timestamp)
                        .description("SDO AIA 171 Angstrom - Corona/Transition Region")
                        .build())
                .aia131(SunImage.newBuilder()
                        .url(NASA_SDO_BASE + "latest_1024_0131.jpg")
                        .timestamp(timestamp)
                        .description("SDO AIA 131 Angstrom - Flares")
                        .build())
                .aia1700(SunImage.newBuilder()
                        .url(NASA_SDO_BASE + "latest_1024_1700.jpg")
                        .timestamp(timestamp)
                        .description("SDO AIA 1700 Angstrom - Photosphere")
                        .build())
                .build();
    }

    // ===== ML PREDICTION =====
    private AuroraPrediction fetchMlPrediction(double lat, double lon) {
        logger.info(lp("fetching ML prediction for lat={}, lon={}"), lat, lon);

        String requestBody = gson.toJson(Map.of("latitude", lat, "longitude", lon));

        String response = webClient.post()
                .uri(auroraMlApiUrl + "/predict")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null) {
            throw new RuntimeException("ML API returned null");
        }

        JsonObject data = gson.fromJson(response, JsonObject.class);

        JsonObject conditions = data.has("conditions") ? data.getAsJsonObject("conditions") : new JsonObject();

        AuroraConditions auroraConditions = AuroraConditions.newBuilder()
                .isDark(conditions.has("is_dark") && conditions.get("is_dark").getAsBoolean())
                .cloudCover(conditions.has("cloud_cover") ? conditions.get("cloud_cover").getAsDouble() : 0.0)
                .kpIndex(conditions.has("kp_index") ? conditions.get("kp_index").getAsDouble() : 0.0)
                .geomagneticStorm(conditions.has("geomagnetic_storm") && conditions.get("geomagnetic_storm").getAsBoolean())
                .moonInterference(conditions.has("moon_interference") && conditions.get("moon_interference").getAsBoolean())
                .build();

        return AuroraPrediction.newBuilder()
                .probability(data.has("probability") ? data.get("probability").getAsDouble() : 0.0)
                .confidence(data.has("confidence") ? data.get("confidence").getAsString() : "unknown")
                .gbProbability(data.has("gb_probability") ? data.get("gb_probability").getAsDouble() : 0.0)
                .xgbProbability(data.has("xgb_probability") ? data.get("xgb_probability").getAsDouble() : 0.0)
                .conditions(auroraConditions)
                .build();
    }

    // ===== NEARBY PREDICTIONS (~315 points) =====
    private List<NearbyPrediction> fetchNearbyPredictions(double centerLat, double centerLon) {
        logger.info(lp("generating nearby predictions for lat={}, lon={}"), centerLat, centerLon);

        // Configuration
        final double MAX_RADIUS_MILES = 250.0;    // ~4 hour drive
        final double RING_SPACING_MILES = 50.0;   // 5 rings
        final double POINT_SPACING_MILES = 15.0;  // arc distance between points

        List<NearbyPrediction> predictions = new ArrayList<>();
        List<Map<String, Object>> locations = new ArrayList<>();

        // Generate rings with variable point density
        for (double radius = RING_SPACING_MILES; radius <= MAX_RADIUS_MILES; radius += RING_SPACING_MILES) {
            // Points per ring = circumference / point spacing
            double circumference = 2 * Math.PI * radius;
            int pointsInRing = (int) Math.round(circumference / POINT_SPACING_MILES);
            double degreesPerPoint = 360.0 / pointsInRing;

            logger.debug(lp("ring radius={} mi, circumference={} mi, points={}"),
                    radius, Math.round(circumference), pointsInRing);

            for (int i = 0; i < pointsInRing; i++) {
                int bearing = (int) Math.round(i * degreesPerPoint) % 360;
                double[] dest = destinationPoint(centerLat, centerLon, radius, bearing);
                locations.add(Map.of(
                        "latitude", dest[0],
                        "longitude", dest[1],
                        "bearing", bearing,
                        "distanceMiles", (int) radius
                ));
            }
        }

        logger.info(lp("generated {} nearby coordinates"), locations.size());

        // Call batch prediction API
        try {
            String requestBody = gson.toJson(Map.of("locations",
                    locations.stream().map(loc -> Map.of(
                            "latitude", loc.get("latitude"),
                            "longitude", loc.get("longitude")
                    )).toList()));

            String response = webClient.post()
                    .uri(auroraMlApiUrl + "/predict/batch")
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null) {
                JsonObject data = gson.fromJson(response, JsonObject.class);
                JsonArray predictionArray = data.getAsJsonArray("predictions");

                for (int i = 0; i < predictionArray.size() && i < locations.size(); i++) {
                    JsonObject pred = predictionArray.get(i).getAsJsonObject();
                    Map<String, Object> loc = locations.get(i);

                    predictions.add(NearbyPrediction.newBuilder()
                            .lat((Double) loc.get("latitude"))
                            .lon((Double) loc.get("longitude"))
                            .bearing((Integer) loc.get("bearing"))
                            .distanceMiles((Integer) loc.get("distanceMiles"))
                            .probability(pred.has("probability") ? pred.get("probability").getAsDouble() : 0.0)
                            .cloudCover(pred.has("cloud_cover") && !pred.get("cloud_cover").isJsonNull()
                                    ? pred.get("cloud_cover").getAsDouble() : null)
                            .build());
                }

                logger.info(lp("batch predictions received: {}"), predictions.size());
                return predictions;
            }
        } catch (Exception e) {
            logger.error(lp("batch prediction failed: {}"), e.getMessage());
        }

        // Fallback: return coordinates without predictions
        for (Map<String, Object> loc : locations) {
            predictions.add(NearbyPrediction.newBuilder()
                    .lat((Double) loc.get("latitude"))
                    .lon((Double) loc.get("longitude"))
                    .bearing((Integer) loc.get("bearing"))
                    .distanceMiles((Integer) loc.get("distanceMiles"))
                    .probability(0.0)
                    .cloudCover(null)
                    .build());
        }

        return predictions;
    }

    // Calculate destination point given start, distance (miles), and bearing (degrees)
    private double[] destinationPoint(double lat, double lon, double distanceMiles, double bearingDeg) {
        double R = 3958.8; // Earth radius in miles
        double d = distanceMiles / R;
        double brng = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);

        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng));
        double lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));

        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }

    // ===== ASTRONOMY =====
    private Astronomy calculateAstronomy(double lat, double lon) {
        logger.info(lp("calculating astronomy for lat={}, lon={}"), lat, lon);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));

        // Simplified sun/moon calculations
        // In production, use a proper astronomy library like commons-suncalc
        double sunAltitude = calculateSunAltitude(lat, lon, now);
        boolean sunUp = sunAltitude > 0;

        double moonAltitude = calculateMoonAltitude(lat, lon, now);
        boolean moonUp = moonAltitude > 0;
        double moonIllumination = calculateMoonIllumination(now);
        String moonPhase = getMoonPhaseName(moonIllumination);

        SunTimes sunTimes = SunTimes.newBuilder()
                .isUp(sunUp)
                .altitude(sunAltitude)
                .sunrise("06:30")  // Placeholder - calculate properly
                .sunset("17:30")
                .civilDawn("06:00")
                .civilDusk("18:00")
                .nauticalDawn("05:30")
                .nauticalDusk("18:30")
                .astronomicalDawn("05:00")
                .astronomicalDusk("19:00")
                .build();

        MoonInfo moonInfo = MoonInfo.newBuilder()
                .isUp(moonUp)
                .altitude(moonAltitude)
                .moonrise(null)
                .moonset(null)
                .phase(moonPhase)
                .illumination(moonIllumination)
                .build();

        return Astronomy.newBuilder()
                .sun(sunTimes)
                .moon(moonInfo)
                .build();
    }

    // Simplified sun altitude calculation
    private double calculateSunAltitude(double lat, double lon, ZonedDateTime time) {
        int dayOfYear = time.getDayOfYear();
        double hour = time.getHour() + time.getMinute() / 60.0;

        // Very rough approximation
        double declination = -23.45 * Math.cos(Math.toRadians(360.0 / 365.0 * (dayOfYear + 10)));
        double hourAngle = 15.0 * (hour - 12.0 + lon / 15.0);

        double sinAlt = Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(declination))
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(declination)) * Math.cos(Math.toRadians(hourAngle));

        return Math.toDegrees(Math.asin(sinAlt));
    }

    private double calculateMoonAltitude(double lat, double lon, ZonedDateTime time) {
        // Simplified - moon altitude varies significantly
        return -10.0; // Placeholder
    }

    private double calculateMoonIllumination(ZonedDateTime time) {
        // Synodic month is ~29.53 days
        long daysSinceNewMoon = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.of(2024, 1, 11), // Known new moon
                time.toLocalDate()
        ) % 30;

        // Rough illumination calculation
        return Math.abs(Math.cos(Math.PI * daysSinceNewMoon / 14.765)) * 100;
    }

    private String getMoonPhaseName(double illumination) {
        if (illumination < 5) return "New Moon";
        if (illumination < 35) return "Waxing Crescent";
        if (illumination < 65) return "First Quarter";
        if (illumination < 95) return "Waxing Gibbous";
        return "Full Moon";
    }

    // ===== LIGHT POLLUTION =====
    private LightPollution estimateLightPollution(double lat, double lon) {
        // Simplified estimation based on latitude (higher latitudes tend to have less light pollution)
        // In production, use VIIRS data
        int bortle = 5; // Default suburban

        if (Math.abs(lat) > 60) {
            bortle = 2; // Northern/southern regions typically darker
        } else if (Math.abs(lat) > 45) {
            bortle = 4;
        }

        String[] descriptions = {
                "", "Excellent dark sky", "Typical dark site", "Rural sky",
                "Rural/suburban transition", "Suburban sky", "Bright suburban",
                "Suburban/urban transition", "City sky", "Inner city sky"
        };

        return LightPollution.newBuilder()
                .bortle(bortle)
                .description(descriptions[bortle])
                .artificialBrightness(null)
                .build();
    }

    // ===== WEBCAMS =====
    private List<Webcam> loadWebcams() {
        logger.info(lp("loading webcams from resource file"));
        try (InputStream is = getClass().getResourceAsStream("/webcams.json");
             InputStreamReader reader = new InputStreamReader(is)) {
            Type listType = new TypeToken<List<Webcam>>(){}.getType();
            return gson.fromJson(reader, listType);
        } catch (Exception e) {
            logger.warn(lp("failed to load webcams: {}"), e.getMessage());
            return getDefaultWebcams();
        }
    }

    private List<Webcam> getDefaultWebcams() {
        return List.of(
                Webcam.newBuilder()
                        .name("Churchill Northern Studies Centre")
                        .location("Churchill, Manitoba, Canada")
                        .url("https://www.youtube.com/embed/Cx5lHPhikDM")
                        .streamType("youtube")
                        .lat(58.7684)
                        .lon(-94.1650)
                        .active(true)
                        .build(),
                Webcam.newBuilder()
                        .name("Fairbanks Aurora Cam")
                        .location("Fairbanks, Alaska, USA")
                        .url("https://www.youtube.com/embed/SHGSHAXAeJA")
                        .streamType("youtube")
                        .lat(64.8378)
                        .lon(-147.7164)
                        .active(true)
                        .build(),
                Webcam.newBuilder()
                        .name("Abisko Sky Station")
                        .location("Abisko, Sweden")
                        .url("https://www.youtube.com/embed/tHJeMrJhE8c")
                        .streamType("youtube")
                        .lat(68.3496)
                        .lon(18.8306)
                        .active(true)
                        .build()
        );
    }

    // ===== HELPERS =====
    private String getTimezone(double lat, double lon) {
        // Rough timezone estimation from longitude
        int offset = (int) Math.round(lon / 15.0);
        if (offset >= 0) {
            return "UTC+" + offset;
        }
        return "UTC" + offset;
    }

    private void cacheData(String key, String value, long duration, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, duration, unit);
        } catch (Exception e) {
            logger.warn(lp("cache write failed for key={}: {}"), key, e.getMessage());
        }
    }

    private double parseDoubleOrDefault(JsonElement element, double defaultValue) {
        if (element == null || element.isJsonNull()) return defaultValue;
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
