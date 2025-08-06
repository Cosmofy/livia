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

@DgsComponent
public class PlanetsDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(PlanetsDataFetcher.class);
    private final WebClient webClient;

    @Autowired
    public PlanetsDataFetcher(Gson gson, WebClient webClient) {
        this.webClient = webClient;
    }

    @DgsQuery
    public List<Planet> planets() {
        logger.info("Fetching planets information from JPL");

        // A. Build the OpenAI helper
        OpenAIClientAsync client = OpenAIOkHttpClientAsync.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build();

        // B. Fetch fresh valid input from API
        List<CompletableFuture<Planet>> futures = new ArrayList<>();
        for (int i = 199; i < 999; i += 100) {
            String api_source = "https://ssd.jpl.nasa.gov/api/horizons.api?format=text&COMMAND='" + i + "'&MAKE_EPHEM=NO";
            String response = webClient
                    .get()
                    .uri(api_source)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            CompletableFuture<Planet> planetFuture = extractFields(client, response);

            futures.add(planetFuture);
        }

        List<Planet> planets = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return planets;
    }

    private static CompletableFuture<Planet> extractFields(OpenAIClientAsync client, String response) {
        String instruction = """
                Extract a single well-formatted JSON object that exactly matches the `Planet` schema below.
                Only respond with **raw JSON** — no Markdown, no code formatting, no explanations.
                
                ### Formatting Rules:
                - ALWAYS Use **scientific notation** in standard `e` notation format (e.g. `6.085e10`, `1.600e3`, `1.23e-4`)
                  - Do NOT use `×`, superscript numerals, or math formatting
                  - All numeric fields must be valid JSON numbers (not strings)
                - **Convert ALL appropriate numeric values** to scientific notation except:
                  - Fields of type `OrbitalRadiation` (those use plain floats)
                  - Fields where precision demands decimal (e.g., `gravity`, `obliquity`, `flattening`)
                - If a field includes `<` or `~` in the API, DO NOT **retain** those characters.
                - NEVER return math expressions like `3.39619e3 - (...)` — evaluate and return the numeric result only (e.g., `3.3762e3`)
                - If a field is missing, return `null`.
                - For `atmosphere`: always attempt to return **at least 2–3 gases** with percentages if known.
                - Use full compliance with the schema types below.
                
                ### NOT IN API (MUST be filled in):
                - The following fields are not in the JPL API but **must be filled in creatively**:
                  - `visual`: A date or phrase describing first known observation of the planet.
                  - `description`: A short inviting phrase about the planet (1 sentence).
                  - `expandedDescription`: A 50-word whimsical or scientific summary.
                  - `facts`: A list of 3–5 short, engaging facts about the planet.
                  - `orbitalInclination`: Use best estimate from NASA or other sources.
                  - `atmosphere`: Include AT LEAST 5–10 known gases with molar mass, formula WITH SUBSCRIPT (e.g., H₂O), and estimated percentage if available.
                
                Here is the full schema:
                # API 4: Planetary Information
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
                    volume: Float # 6.2526 × 10¹³ example format
                    moons: Int
                    rings: Int
                
                    # GRAVITATIONAL PROPERTIES:
                    gravityEquatorial: Float # m/s^2 — Gravity at equator
                    gravityPolar: Float # m/s^2 — Gravity at poles
                    escapeVelocity: Float # km/s — Speed required to escape gravitational pull
                    flattening: Float # Unitless — Equatorial bulge (1 − polar/equatorial radius)
                    gravitationalParameter: Float # km^3/s^2 — Standard GM value
                    gravitationalParameterUncertainty: Float # km^3/s^2 — ±1σ uncertainty in GM
                    mass: String # 6.2526 × 10¹³ example format
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
                """;

        String prompt = instruction + "\nResponse: " + response;
        ResponseCreateParams createParams = ResponseCreateParams.builder()
                .input(prompt)
                .model(ChatModel.GPT_4_1_MINI)
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
                    System.out.println("OpenAI JSON Response:\n" + rawJson);
                    Planet parsedPlanet = new Gson().fromJson(rawJson, Planet.class);
                    future.complete(parsedPlanet);
                })
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }


}
