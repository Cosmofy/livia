package xyz.arryan.livia.datafetchers;


import com.google.gson.Gson;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.models.ChatModel;
import com.openai.models.responses.ResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import xyz.arryan.livia.codegen.types.Planet;
import xyz.arryan.livia.codegen.types.OrbitalRadiation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.time.Duration;

@DgsComponent
public class PlanetsDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(PlanetsDataFetcher.class);
    private static final String LOG_PREFIX = "API 4: PLANETS | ";
    private static String lp(String msg) { return LOG_PREFIX + msg; }
    private final WebClient webClient;

    @Autowired
    public PlanetsDataFetcher(Gson gson, WebClient webClient) {
        this.webClient = webClient;
    }

    @DgsQuery
    public List<Planet> planets() {
        // ===== A. Entry =====
        logger.info(lp("entry: fetching planets from JPL Horizons"));

        // A. Build the OpenAI helper
        OpenAIClientAsync client = OpenAIOkHttpClientAsync.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();

        // ===== B. Fetch fresh valid input from API =====
        List<CompletableFuture<Planet>> futures = new ArrayList<>();
        for (int i = 199; i < 999; i += 100) {
            // ===== B. Fetch text payload for this body ID =====
            final Instant t0 = Instant.now();
            logger.info(lp("fetch begin: id={}"), i);
            String api_source = "https://ssd.jpl.nasa.gov/api/horizons.api?format=text&COMMAND='" + i + "'&MAKE_EPHEM=NO";
            String response = webClient
                    .get()
                    .uri(api_source)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            final long ms = Duration.between(t0, Instant.now()).toMillis();
            logger.info(lp("fetch complete: id={} in {} ms"), i, ms);
            if (response == null || response.isEmpty()) {
                logger.error(lp("empty response for id={}"), i);
            } else {
                logger.debug(lp("response preview for id={}: {}"), i, response.substring(0, Math.min(300, response.length())).replaceAll("\\s+", " "));
            }

            CompletableFuture<Planet> planetFuture = extractFields(client, response);

            futures.add(planetFuture);
        }

        List<Planet> planets = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        logger.info(lp("transform complete: mapped planets={}"), planets.size());
        return planets;
    }

    private static CompletableFuture<Planet> extractFields(OpenAIClientAsync client, String response) {
        // ===== C. LLM extraction via OpenAI =====
        logger.info(lp("openai begin: extract fields from Horizons text"));
        final Instant llmStart = Instant.now();
        String instruction = """
                Your task is to extract a single well-formatted JSON object from some input text. Your response must exactly matches the `Planet` schema below. Only respond with "raw JSON", no Markdown, no code formatting, and no explanations.
                
                 Challenge:\s
                 The schema contains much more information to return than the input text. Use best estimate from NASA or other sources (e.g., https://nssdc.gsfc.nasa.gov/planetary/factsheet/). You must fill these values creatively or from your own knowledge:
                 1. visual: A date or phrase describing first known observation of the planet.
                 2. description: A singular short inviting phrase about the planet.
                 3. expandedDescription: A 40-60 words whimsical or scientific summary.
                 4. facts: A list of 4–6 short, engaging true facts about the planet.
                 5. atmosphere: Include 5–10 known gases on the planet with molar mass, formula with superscript (e.g., H₂O), and estimated percentage,\s
                 5. moons: on the planet
                 6. rings: on the planet
                
                 General Guidelines:
                 - Fill all fields unless its absolutely unknown then return null.
                 - Do not include the unit in any field
                 - All numeric fields must be valid JSON numbers (not strings)
                 - Use only scientific notation in standard e notation format (e.g. "6.085e10", "1.23e-4") for values that are not whole numbers or precision demands decimal (e.g., `gravity`, `obliquity`, `flattening`) and is not usually that big or small of an exponent. Do not use `×`, superscript numerals, or math formatting.
                 - If a field includes `<` or `~` in the API, DO NOT retain those characters.
                 - Compute the value of all math expressions if any, and return the numeric result only
                 - Every value above 1,000,000 must use scientific notation
                
                 Schema to match:
                 type Planet {
                
                     # PHYSICAL PROPERTIES:
                     name: String! # Planet name (e.g., "Venus", "Uranus")
                     id: Int # Planet ID (JPL Horizons body ID)
                     lastUpdated: String # Date this data was last revised
                     density: Float # g/cm^3 — Average planetary density
                     temperature: Int # K — Atmospheric temperature at 1 bar (if applicable)
                     pressure: Float # bar — Surface atmospheric pressure
                     radiusEquatorial: Float # km — Radius at equator (1 bar level if gaseous)
                     radiusPolar: Float # km — Radius at poles
                     radiusCore: Float # km — Estimated radius of the rocky core (if known)
                     radiusHillsSphere: Float # Rp — Radius of Hill sphere, in planetary radii
                     angularDiameter: Float # arcseconds — Max angular diameter seen from Earth
                     momentOfInertia: Float # Unitless — Ratio of rotational inertia to mass-radius^2
                     volumetricMeanRadius: Float # km — Volume-averaged mean radius
                     volume: Float # 6.2526e13 example format
                     moons: Int
                     rings: Int
                
                     # GRAVITATIONAL PROPERTIES:
                     gravityEquatorial: Float # m/s^2 — Gravity at equator
                     gravityPolar: Float # m/s^2 — Gravity at poles
                     escapeVelocity: Float # km/s — Speed required to escape gravitational pull
                     flattening: Float # Unitless — Equatorial bulge (1 − polar/equatorial radius)
                     gravitationalParameter: Float # km^3/s^2 — Standard GM value
                     gravitationalParameterUncertainty: Float # km^3/s^2 — ±1σ uncertainty in GM
                     mass: Float # 6.2526e13 example format
                     rockyCoreMass: Float # Mc/M — Core mass as a fraction of total mass
                
                     # ORBITAL DYNAMICS & ROTATION:
                     orbitalVelocity: Float # km/s — Mean orbital speed
                     siderealOrbitPeriodD: Float # d — Orbit period in days
                     siderealOrbitPeriodY: Float # y — Orbit period in years
                     obliquityToOrbit: Float # degrees — Axial tilt relative to orbital plane
                     siderealRotationRate: Float # rad/s — Angular rotation rate
                     siderealRotationPeriod: Float # h or d — Time to complete 1 full rotation
                     solarDayLength: Float # h or d — Length of a solar day on the planet
                
                     # ATMOSPHERIC & OPTICAL:
                     albedo: Float # Unitless — Fraction of light reflected
                     visualMagnitude: Float # mag — Brightness seen from Earth (1 AU)
                     visualMagnitudeOpposition: Float # mag — Brightness at opposition
                
                     # SPECIALIZED PARAMETERS:
                     rocheLimit: Float # Rp — Distance within which a satellite disintegrates
                
                     solarConstant: OrbitalRadiation # W/m^2 — Solar energy received
                     maxIR: OrbitalRadiation # W/m^2 — Max planetary infrared output
                     minIR: OrbitalRadiation # W/m^2 — Min planetary infrared output
                
                     description: String # a short inviting phrase about the planet. example: A deep blue and distant gas giant with turbulent weather.
                     expandedDescription: String # description: a playful description about the planet. around 50 words example: Neptune, the eighth planet from the Sun, is known for its striking blue color and dynamic atmosphere, which includes the fastest winds in the solar system. This gas giant, discovered in 1846, has a prominent role in understanding planetary formation and atmospheric dynamics due to its complex weather patterns and unique position in the outer solar system.
                     facts: [String] # array of 3-5 facts. example fact: "A day on Neptune is 16 hours, but a year lasts 165 Earth years. Barely one Neptune year has passed since the planet’s discovery in 1846."
                     visual: String # first sight of the planet. example: neptune: September 23, 1846, could be a range or for earth, its was already discovered.
                     orbitalInclination: Float # not in the api, have to fit the value from elsewhere
                     atmosphere: [Component] # all known gaseous components in a planet's atmosphere. example: oxygen gas, 32, O₂, 18.8%
                 }
                
                 type Component {
                     name: String
                     molar: Int # molar mass
                     formula: String # example He, Ar, CO₂, H₂O
                     percentage: Float # example: 57.7%
                 }
                
                 type OrbitalRadiation {
                     perihelion: Float # perihelion
                     aphelion: Float # aphelion
                     mean: Float # mean
                 }
                
                 Example Response:
                       {
                         "moons": 14,
                         "name": "Neptune",
                         "obliquityToOrbit": 28.32,
                         "orbitalInclination": 1.76917,
                         "orbitalVelocity": 5.43,
                         "albedo": 0.41,
                         "angularDiameter": 2.3,
                         "atmosphere": [
                           {
                             "formula": "H₂",
                             "molar": 2,
                             "name": "Molecular hydrogen",
                             "percentage": 80
                           },
                           {
                             "formula": "He",
                             "molar": 4,
                             "name": "Helium",
                             "percentage": 19
                           },
                           {
                             "formula": "CH₄",
                             "molar": 16,
                             "name": "Methane",
                             "percentage": 1.5
                           },
                           {
                             "formula": "NH₃",
                             "molar": 17,
                             "name": "Ammonia (trace)",
                             "percentage": 0.2
                           },
                           {
                             "formula": "H₂O",
                             "molar": 18,
                             "name": "Water vapor (trace)",
                             "percentage": 0.15
                           },
                           {
                             "formula": "C₂H₆",
                             "molar": 30,
                             "name": "Ethane (trace)",
                             "percentage": 0.05
                           },
                           {
                             "formula": "C₂H₂",
                             "molar": 26,
                             "name": "Acetylene (trace)",
                             "percentage": 0.03
                           },
                           {
                             "formula": "N₂",
                             "molar": 28,
                             "name": "Molecular nitrogen (trace)",
                             "percentage": 0.02
                           }
                         ],
                         "density": 1.638,
                         "description": "A distant, deep-blue ice giant with dynamic weather and icy interiors.",
                         "escapeVelocity": 23.5,
                         "expandedDescription": "Neptune, the eighth planet from the Sun, is a cold blue ice giant marked by strong winds, methane-rich clouds, and a mysterious interior. Discovered as a planet in 1846, it challenges our understanding of planetary dynamics and hosts a compact system of rings and diverse moons in a remote icy realm.",
                         "facts": [
                           "Neptune completes one orbit in about 164.8 Earth years.",
                           "A day on Neptune lasts about 16.11 hours.",
                           "Neptune's strong methane absorption gives it a striking blue color.",
                           "Neptune has 14 known moons and a faint system of rings."
                         ],
                         "flattening": 0.0171,
                         "gravitationalParameter": 6835099.97,
                         "gravitationalParameterUncertainty": 10,
                         "gravityEquatorial": 11.15,
                         "gravityPolar": 11.41,
                         "id": 899,
                         "lastUpdated": "2021-05-03",
                         "mass": 1.02409e+26,
                         "maxIR": {
                           "aphelion": 0.52,
                           "mean": 0.52,
                           "perihelion": 0.52
                         },
                         "minIR": {
                           "aphelion": 0.52,
                           "mean": 0.52,
                           "perihelion": 0.52
                         },
                         "pressure": 1,
                         "radiusEquatorial": 24766,
                         "momentOfInertia": null,
                         "radiusCore": null,
                         "radiusHillsSphere": 4700,
                         "radiusPolar": 24342,
                         "rocheLimit": 2.98,
                         "rings": 5,
                         "siderealOrbitPeriodD": 60189,
                         "rockyCoreMass": null,
                         "siderealOrbitPeriodY": 164.788501027,
                         "siderealRotationRate": 0.000108338,
                         "siderealRotationPeriod": 16.11,
                         "solarConstant": {
                           "perihelion": 1.54,
                           "mean": 1.51,
                           "aphelion": 1.49
                         },
                         "solarDayLength": 16.11,
                         "temperature": 72,
                         "visual": "First telescopic sighting: 1612 (Galileo); recognized as a planet: September 23, 1846",
                         "visualMagnitude": -6.87,
                         "visualMagnitudeOpposition": 7.84,
                         "volume": 6.254e13,
                         "volumetricMeanRadius": 24624
                       }
                
                """;

        String prompt = instruction + "\nResponse: " + response;
        ResponseCreateParams createParams = ResponseCreateParams.builder()
                .input(prompt)
                .model(ChatModel.GPT_5_MINI)
                .build();
        CompletableFuture<Planet> future = new CompletableFuture<>();
        client.responses()
                .create(createParams)
                .thenAccept(summary -> {
                    StringBuilder output = new StringBuilder();

                    summary.output().stream()
                            .flatMap(item -> item.message().stream())
                            .flatMap(message -> message.content().stream())
                            .flatMap(content -> content.outputText().stream())
                            .forEach(outputText -> output.append(outputText.text()).append("\n"));

                    String rawJson = output.toString().trim();
                    logger.debug(lp("openai raw json (truncated 400): {}"), rawJson.substring(0, Math.min(400, rawJson.length())));
                    Planet parsedPlanet = new Gson().fromJson(rawJson, Planet.class);
                    final long llmMs = Duration.between(llmStart, Instant.now()).toMillis();
                    logger.info(lp("openai complete: {} ms"), llmMs);
                    future.complete(parsedPlanet);
                })
                .exceptionally(ex -> {
                    logger.error(lp("openai failed: {}"), ex.toString(), ex);
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }


}
