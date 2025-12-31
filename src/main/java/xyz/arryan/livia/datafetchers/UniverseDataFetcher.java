package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import xyz.arryan.livia.codegen.types.*;

import java.util.List;
import java.util.stream.Collectors;

@DgsComponent
public class UniverseDataFetcher {
    private static final Logger logger = LoggerFactory.getLogger(UniverseDataFetcher.class);
    private static final String LOG_PREFIX = "UNIVERSE | ";
    private static final String COLLECTION = "universe";

    private static String lp(String msg) { return LOG_PREFIX + msg; }

    private final MongoTemplate mongoTemplate;
    private Document cachedUniverse;

    @Autowired
    public UniverseDataFetcher(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
        logger.info(lp("initialized with MongoDB backend"));
    }

    private Document getUniverseDocument() {
        if (cachedUniverse == null) {
            cachedUniverse = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is("observable-universe")),
                Document.class,
                COLLECTION
            );
        }
        return cachedUniverse;
    }

    // ==================== ROOT QUERY ====================

    @DgsQuery
    public Universe universe() {
        logger.info(lp("fetching universe from MongoDB"));
        Document doc = getUniverseDocument();
        return Universe.newBuilder()
                .name(doc.getString("name"))
                .age(doc.getDouble("age"))
                .diameter(doc.getDouble("diameter"))
                .build();
    }

    // ==================== UNIVERSE LEVEL ====================

    private boolean matchesFilter(List<String> names, String value) {
        if (names == null || names.isEmpty()) return true;
        List<String> filtered = names.stream().filter(n -> n != null && !n.isEmpty()).collect(Collectors.toList());
        if (filtered.isEmpty()) return true;
        return filtered.stream().anyMatch(n -> n.equalsIgnoreCase(value));
    }

    @DgsData(parentType = "Universe", field = "superclusters")
    public List<Supercluster> universeSuperClusters(@InputArgument List<String> names) {
        Document doc = getUniverseDocument();
        List<Document> superclusters = doc.getList("superclusters", Document.class);
        return superclusters.stream()
                .filter(sc -> matchesFilter(names, sc.getString("name")))
                .map(this::docToSupercluster)
                .collect(Collectors.toList());
    }

    // ==================== SUPERCLUSTER LEVEL ====================

    @DgsData(parentType = "Supercluster", field = "galaxyClusters")
    public List<GalaxyCluster> superclusterGalaxyClusters(DgsDataFetchingEnvironment dfe, @InputArgument List<String> names) {
        Supercluster supercluster = dfe.getSource();
        Document scDoc = findSuperclusterDoc(supercluster.getName());
        if (scDoc == null) return List.of();

        List<Document> clusters = scDoc.getList("galaxyClusters", Document.class);
        if (clusters == null) return List.of();

        return clusters.stream()
                .filter(c -> matchesFilter(names, c.getString("name")))
                .map(this::docToGalaxyCluster)
                .collect(Collectors.toList());
    }

    // ==================== GALAXY CLUSTER LEVEL ====================

    @DgsData(parentType = "GalaxyCluster", field = "galaxies")
    public List<Galaxy> galaxyClusterGalaxies(DgsDataFetchingEnvironment dfe, @InputArgument List<String> names) {
        GalaxyCluster cluster = dfe.getSource();
        Document clusterDoc = findGalaxyClusterDoc(cluster.getName());
        if (clusterDoc == null) return List.of();

        List<Document> galaxies = clusterDoc.getList("galaxies", Document.class);
        if (galaxies == null) return List.of();

        return galaxies.stream()
                .filter(g -> matchesFilter(names, g.getString("name")))
                .map(this::docToGalaxy)
                .collect(Collectors.toList());
    }

    // ==================== GALAXY LEVEL ====================

    @DgsData(parentType = "Galaxy", field = "starSystems")
    public List<StarSystem> galaxyStarSystems(DgsDataFetchingEnvironment dfe, @InputArgument List<String> names) {
        Galaxy galaxy = dfe.getSource();
        Document galaxyDoc = findGalaxyDoc(galaxy.getName());
        if (galaxyDoc == null) return List.of();

        List<Document> systems = galaxyDoc.getList("starSystems", Document.class);
        if (systems == null) return List.of();

        return systems.stream()
                .filter(s -> matchesFilter(names, s.getString("name")))
                .map(this::docToStarSystem)
                .collect(Collectors.toList());
    }

    // ==================== STAR SYSTEM LEVEL ====================

    @DgsData(parentType = "StarSystem", field = "star")
    public Star starSystemStar(DgsDataFetchingEnvironment dfe) {
        StarSystem system = dfe.getSource();
        Document systemDoc = findStarSystemDoc(system.getName());
        if (systemDoc == null) return null;

        Document starDoc = systemDoc.get("star", Document.class);
        if (starDoc == null) return null;

        return docToStar(starDoc);
    }

    @DgsData(parentType = "StarSystem", field = "planets")
    public List<Planet> starSystemPlanets(DgsDataFetchingEnvironment dfe, @InputArgument List<String> names) {
        StarSystem system = dfe.getSource();
        Document systemDoc = findStarSystemDoc(system.getName());
        if (systemDoc == null) return List.of();

        List<Document> planets = systemDoc.getList("planets", Document.class);
        if (planets == null) return List.of();

        return planets.stream()
                .filter(p -> matchesFilter(names, p.getString("name")))
                .map(this::docToPlanet)
                .collect(Collectors.toList());
    }

    @DgsData(parentType = "StarSystem", field = "dwarfPlanets")
    public List<DwarfPlanet> starSystemDwarfPlanets(DgsDataFetchingEnvironment dfe, @InputArgument List<String> names) {
        StarSystem system = dfe.getSource();
        Document systemDoc = findStarSystemDoc(system.getName());
        if (systemDoc == null) return List.of();

        List<Document> dwarfPlanets = systemDoc.getList("dwarfPlanets", Document.class);
        if (dwarfPlanets == null) return List.of();

        return dwarfPlanets.stream()
                .filter(dp -> matchesFilter(names, dp.getString("name")))
                .map(this::docToDwarfPlanet)
                .collect(Collectors.toList());
    }

    // ==================== PLANET LEVEL ====================

    @DgsData(parentType = "Planet", field = "satellites")
    public List<Satellite> planetSatellites(DgsDataFetchingEnvironment dfe) {
        Planet planet = dfe.getSource();
        Document planetDoc = findPlanetDoc(planet.getName());
        if (planetDoc == null) return List.of();

        List<Document> satellites = planetDoc.getList("satellites", Document.class);
        if (satellites == null) return List.of();

        return satellites.stream()
                .map(this::docToSatellite)
                .collect(Collectors.toList());
    }

    // ==================== DOCUMENT NAVIGATION HELPERS ====================

    private Document findSuperclusterDoc(String name) {
        Document doc = getUniverseDocument();
        List<Document> superclusters = doc.getList("superclusters", Document.class);
        return superclusters.stream()
                .filter(sc -> name.equals(sc.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private Document findGalaxyClusterDoc(String name) {
        Document doc = getUniverseDocument();
        List<Document> superclusters = doc.getList("superclusters", Document.class);
        for (Document sc : superclusters) {
            List<Document> clusters = sc.getList("galaxyClusters", Document.class);
            if (clusters != null) {
                for (Document cluster : clusters) {
                    if (name.equals(cluster.getString("name"))) {
                        return cluster;
                    }
                }
            }
        }
        return null;
    }

    private Document findGalaxyDoc(String name) {
        Document doc = getUniverseDocument();
        List<Document> superclusters = doc.getList("superclusters", Document.class);
        for (Document sc : superclusters) {
            List<Document> clusters = sc.getList("galaxyClusters", Document.class);
            if (clusters != null) {
                for (Document cluster : clusters) {
                    List<Document> galaxies = cluster.getList("galaxies", Document.class);
                    if (galaxies != null) {
                        for (Document galaxy : galaxies) {
                            if (name.equals(galaxy.getString("name"))) {
                                return galaxy;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private Document findStarSystemDoc(String name) {
        Document galaxyDoc = findGalaxyDoc("Milky Way");
        if (galaxyDoc == null) return null;

        List<Document> systems = galaxyDoc.getList("starSystems", Document.class);
        if (systems == null) return null;

        return systems.stream()
                .filter(s -> name.equals(s.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private Document findPlanetDoc(String name) {
        Document systemDoc = findStarSystemDoc("Sol");
        if (systemDoc == null) return null;

        List<Document> planets = systemDoc.getList("planets", Document.class);
        if (planets == null) return null;

        return planets.stream()
                .filter(p -> name.equalsIgnoreCase(p.getString("name")))
                .findFirst()
                .orElse(null);
    }

    private Galaxy findGalaxyByName(String name) {
        Document galaxyDoc = findGalaxyDoc(name);
        return galaxyDoc != null ? docToGalaxy(galaxyDoc) : null;
    }

    private StarSystem findStarSystemByName(String name) {
        Document systemDoc = findStarSystemDoc(name);
        return systemDoc != null ? docToStarSystem(systemDoc) : null;
    }

    // ==================== DOCUMENT TO TYPE CONVERSION ====================

    private Supercluster docToSupercluster(Document doc) {
        return Supercluster.newBuilder()
                .name(doc.getString("name"))
                .diameter(getDouble(doc, "diameter"))
                .build();
    }

    private GalaxyCluster docToGalaxyCluster(Document doc) {
        return GalaxyCluster.newBuilder()
                .name(doc.getString("name"))
                .diameter(getDouble(doc, "diameter"))
                .galaxyCount(doc.getInteger("galaxyCount"))
                .build();
    }

    private Galaxy docToGalaxy(Document doc) {
        GalaxyType type = null;
        String typeStr = doc.getString("type");
        if (typeStr != null) {
            try {
                type = GalaxyType.valueOf(typeStr.toUpperCase().replace(" ", "_").replace("-", "_"));
            } catch (IllegalArgumentException e) {
                logger.warn(lp("Unknown galaxy type: {}"), typeStr);
            }
        }

        return Galaxy.newBuilder()
                .name(doc.getString("name"))
                .type(type)
                .diameter(getDouble(doc, "diameter"))
                .starCount(doc.getString("starCount"))
                .age(getDouble(doc, "age"))
                .build();
    }

    private StarSystem docToStarSystem(Document doc) {
        return StarSystem.newBuilder()
                .name(doc.getString("name"))
                .age(getDouble(doc, "age"))
                .build();
    }

    private Star docToStar(Document doc) {
        return Star.newBuilder()
                .name(doc.getString("name"))
                .type(doc.getString("type"))
                .classification(doc.getString("classification"))
                .description(doc.getString("description"))
                .expandedDescription(doc.getString("expandedDescription"))
                .facts(doc.getList("facts", String.class))
                .visual(doc.getString("visual"))
                .mass(getDouble(doc, "mass"))
                .volume(getDouble(doc, "volume"))
                .density(getDouble(doc, "density"))
                .radius(getDouble(doc, "radius"))
                .diameter(getDouble(doc, "diameter"))
                .circumference(getDouble(doc, "circumference"))
                .gravity(getDouble(doc, "gravity"))
                .escapeVelocity(getDouble(doc, "escapeVelocity"))
                .luminosity(getDouble(doc, "luminosity"))
                .absoluteMagnitude(getDouble(doc, "absoluteMagnitude"))
                .apparentMagnitude(getDouble(doc, "apparentMagnitude"))
                .age(getDouble(doc, "age"))
                .lifespan(getDouble(doc, "lifespan"))
                .distanceFromEarth(docToStarDistance(doc.get("distanceFromEarth", Document.class)))
                .temperature(docToStarTemperature(doc.get("temperature", Document.class)))
                .rotation(docToStarRotation(doc.get("rotation", Document.class)))
                .structure(docToStarStructure(doc.get("structure", Document.class)))
                .composition(docToStarComposition(doc.get("composition", Document.class)))
                .magneticField(docToStarMagnetic(doc.get("magneticField", Document.class)))
                .energy(docToStarEnergy(doc.get("energy", Document.class)))
                .build();
    }

    private StarDistance docToStarDistance(Document doc) {
        if (doc == null) return null;
        return StarDistance.newBuilder()
                .mean(getDouble(doc, "mean"))
                .perihelion(getDouble(doc, "perihelion"))
                .aphelion(getDouble(doc, "aphelion"))
                .lightMinutes(getDouble(doc, "lightMinutes"))
                .build();
    }

    private StarTemperature docToStarTemperature(Document doc) {
        if (doc == null) return null;
        return StarTemperature.newBuilder()
                .core(getDouble(doc, "core"))
                .radiativeZone(getDouble(doc, "radiativeZone"))
                .convectiveZone(getDouble(doc, "convectiveZone"))
                .photosphere(getDouble(doc, "photosphere"))
                .chromosphere(getDouble(doc, "chromosphere"))
                .corona(getDouble(doc, "corona"))
                .build();
    }

    private StarRotation docToStarRotation(Document doc) {
        if (doc == null) return null;
        return StarRotation.newBuilder()
                .equatorial(getDouble(doc, "equatorial"))
                .polar(getDouble(doc, "polar"))
                .meanSidereal(getDouble(doc, "meanSidereal"))
                .axialTilt(getDouble(doc, "axialTilt"))
                .build();
    }

    private StarStructure docToStarStructure(Document doc) {
        if (doc == null) return null;
        return StarStructure.newBuilder()
                .coreRadius(getDouble(doc, "coreRadius"))
                .radiativeZoneOuter(getDouble(doc, "radiativeZoneOuter"))
                .convectiveZoneOuter(getDouble(doc, "convectiveZoneOuter"))
                .photosphereThickness(getDouble(doc, "photosphereThickness"))
                .chromosphereThickness(getDouble(doc, "chromosphereThickness"))
                .build();
    }

    private StarComposition docToStarComposition(Document doc) {
        if (doc == null) return null;
        return StarComposition.newBuilder()
                .hydrogen(getDouble(doc, "hydrogen"))
                .helium(getDouble(doc, "helium"))
                .oxygen(getDouble(doc, "oxygen"))
                .carbon(getDouble(doc, "carbon"))
                .neon(getDouble(doc, "neon"))
                .iron(getDouble(doc, "iron"))
                .build();
    }

    private StarMagneticField docToStarMagnetic(Document doc) {
        if (doc == null) return null;
        return StarMagneticField.newBuilder()
                .polarFieldStrength(getDouble(doc, "polarFieldStrength"))
                .sunspotFieldStrength(getDouble(doc, "sunspotFieldStrength"))
                .cycleLength(getInt(doc, "cycleLength"))
                .currentCycle(doc.getInteger("currentCycle"))
                .build();
    }

    private StarEnergy docToStarEnergy(Document doc) {
        if (doc == null) return null;
        return StarEnergy.newBuilder()
                .powerOutput(getDouble(doc, "powerOutput"))
                .solarConstant(getDouble(doc, "solarConstant"))
                .build();
    }

    private Planet docToPlanet(Document doc) {
        // Convert boolean rings to int (0 or number of rings if present)
        Integer rings = null;
        Object ringsVal = doc.get("rings");
        if (ringsVal instanceof Boolean) {
            rings = ((Boolean) ringsVal) ? 1 : 0;
        } else if (ringsVal instanceof Integer) {
            rings = (Integer) ringsVal;
        }

        return Planet.newBuilder()
                .name(doc.getString("name"))
                .id(doc.getInteger("id"))
                .lastUpdated(doc.getString("lastUpdated"))
                .description(doc.getString("description"))
                .expandedDescription(doc.getString("expandedDescription"))
                .facts(doc.getList("facts", String.class))
                .visual(doc.getString("visual"))
                .moons(doc.getInteger("moons"))
                .rings(rings)
                // Physical
                .mass(getDouble(doc, "mass"))
                .volume(getDouble(doc, "volume"))
                .density(getDouble(doc, "density"))
                .temperature(getInt(doc, "temperature"))
                .pressure(getDouble(doc, "pressure"))
                .radiusEquatorial(getDouble(doc, "radiusEquatorial"))
                .radiusPolar(getDouble(doc, "radiusPolar"))
                .radiusCore(getDouble(doc, "radiusCore"))
                .radiusHillsSphere(getDouble(doc, "radiusHillsSphere"))
                .angularDiameter(getDouble(doc, "angularDiameter"))
                .momentOfInertia(getDouble(doc, "momentOfInertia"))
                .volumetricMeanRadius(getDouble(doc, "volumetricMeanRadius"))
                // Gravitational
                .gravityEquatorial(getDouble(doc, "gravityEquatorial"))
                .gravityPolar(getDouble(doc, "gravityPolar"))
                .escapeVelocity(getDouble(doc, "escapeVelocity"))
                .flattening(getDouble(doc, "flattening"))
                .gravitationalParameter(getDouble(doc, "gravitationalParameter"))
                .gravitationalParameterUncertainty(getDouble(doc, "gravitationalParameterUncertainty"))
                .rockyCoreMass(getDouble(doc, "rockyCoreMass"))
                // Orbital & Rotation
                .orbitalVelocity(getDouble(doc, "orbitalVelocity"))
                .orbitalInclination(getDouble(doc, "orbitalInclination"))
                .siderealOrbitPeriodD(getDouble(doc, "siderealOrbitPeriodD"))
                .siderealOrbitPeriodY(getDouble(doc, "siderealOrbitPeriodY"))
                .obliquityToOrbit(getDouble(doc, "obliquityToOrbit"))
                .siderealRotationRate(getDouble(doc, "siderealRotationRate"))
                .siderealRotationPeriod(getDouble(doc, "siderealRotationPeriod"))
                .solarDayLength(getDouble(doc, "solarDayLength"))
                // Atmospheric & Optical
                .albedo(getDouble(doc, "albedo"))
                .visualMagnitude(getDouble(doc, "visualMagnitude"))
                .visualMagnitudeOpposition(getDouble(doc, "visualMagnitudeOpposition"))
                // Specialized
                .rocheLimit(getDouble(doc, "rocheLimit"))
                // Position
                .positionX(getDouble(doc, "positionX"))
                .positionY(getDouble(doc, "positionY"))
                .positionZ(getDouble(doc, "positionZ"))
                .build();
    }

    private Satellite docToSatellite(Document doc) {
        return Satellite.newBuilder()
                .name(doc.getString("name"))
                .description(doc.getString("description"))
                .expandedDescription(doc.getString("expandedDescription"))
                .facts(doc.getList("facts", String.class))
                .visual(doc.getString("visual"))
                .mass(getDouble(doc, "mass"))
                .volume(getDouble(doc, "volume"))
                .density(getDouble(doc, "density"))
                .radius(getDouble(doc, "radius"))
                .radiusEquatorial(getDouble(doc, "radiusEquatorial"))
                .radiusPolar(getDouble(doc, "radiusPolar"))
                .gravity(getDouble(doc, "gravity"))
                .escapeVelocity(getDouble(doc, "escapeVelocity"))
                .albedo(getDouble(doc, "albedo"))
                .distanceFromPlanet(docToSatelliteDistance(doc.get("distanceFromEarth", Document.class)))
                .orbitalPeriod(getDouble(doc, "orbitalPeriod"))
                .synodicPeriod(getDouble(doc, "synodicPeriod"))
                .rotationPeriod(getDouble(doc, "rotationPeriod"))
                .orbitalVelocity(getDouble(doc, "orbitalVelocity"))
                .orbitalInclination(getDouble(doc, "orbitalInclination"))
                .axialTilt(getDouble(doc, "axialTilt"))
                .temperature(docToSatelliteTemperature(doc.get("temperature", Document.class)))
                .structure(docToSatelliteStructure(doc.get("structure", Document.class)))
                .composition(docToSatelliteComposition(doc.get("composition", Document.class)))
                .atmosphere(docToSatelliteAtmosphere(doc.get("atmosphere", Document.class)))
                .magneticField(docToSatelliteMagnetic(doc.get("magneticField", Document.class)))
                .recessionRate(getDouble(doc, "recessionRate"))
                .tidallyLocked(doc.getBoolean("tidallyLocked"))
                .age(getDouble(doc, "age"))
                .build();
    }

    private SatelliteDistance docToSatelliteDistance(Document doc) {
        if (doc == null) return null;
        return SatelliteDistance.newBuilder()
                .mean(getDouble(doc, "mean"))
                .perigee(getDouble(doc, "perigee"))
                .apogee(getDouble(doc, "apogee"))
                .build();
    }

    private SatelliteTemperature docToSatelliteTemperature(Document doc) {
        if (doc == null) return null;
        return SatelliteTemperature.newBuilder()
                .mean(getDouble(doc, "mean"))
                .max(getDouble(doc, "max"))
                .min(getDouble(doc, "min"))
                .dayside(getDouble(doc, "dayside"))
                .nightside(getDouble(doc, "nightside"))
                .build();
    }

    private SatelliteStructure docToSatelliteStructure(Document doc) {
        if (doc == null) return null;
        return SatelliteStructure.newBuilder()
                .innerCoreRadius(getDouble(doc, "innerCoreRadius"))
                .outerCoreRadius(getDouble(doc, "outerCoreRadius"))
                .mantleRadius(getDouble(doc, "mantleRadius"))
                .crustThicknessNearside(getDouble(doc, "crustThicknessNearside"))
                .crustThicknessFarside(getDouble(doc, "crustThicknessFarside"))
                .build();
    }

    private SatelliteComposition docToSatelliteComposition(Document doc) {
        if (doc == null) return null;
        List<Document> crustList = doc.getList("crust", Document.class);
        List<SatelliteElement> crust = null;
        if (crustList != null) {
            crust = crustList.stream()
                    .map(e -> SatelliteElement.newBuilder()
                            .element(e.getString("element"))
                            .symbol(e.getString("symbol"))
                            .percentage(getDouble(e, "percentage"))
                            .build())
                    .collect(Collectors.toList());
        }
        return SatelliteComposition.newBuilder()
                .crust(crust)
                .build();
    }

    private SatelliteAtmosphere docToSatelliteAtmosphere(Document doc) {
        if (doc == null) return null;
        List<Document> componentsList = doc.getList("components", Document.class);
        List<SatelliteAtmosphereComponent> components = null;
        if (componentsList != null) {
            components = componentsList.stream()
                    .map(c -> SatelliteAtmosphereComponent.newBuilder()
                            .name(c.getString("name"))
                            .formula(c.getString("formula"))
                            .percentage(getDouble(c, "percentage"))
                            .build())
                    .collect(Collectors.toList());
        }
        return SatelliteAtmosphere.newBuilder()
                .surfacePressure(getDouble(doc, "surfacePressure"))
                .components(components)
                .build();
    }

    private SatelliteMagneticField docToSatelliteMagnetic(Document doc) {
        if (doc == null) return null;
        return SatelliteMagneticField.newBuilder()
                .strength(getDouble(doc, "strength"))
                .description(doc.getString("description"))
                .build();
    }

    private DwarfPlanet docToDwarfPlanet(Document doc) {
        return DwarfPlanet.newBuilder()
                .name(doc.getString("name"))
                .description(doc.getString("description"))
                .mass(getDouble(doc, "mass"))
                .build();
    }

    // Helper to handle both Integer and Double types from MongoDB
    private Double getDouble(Document doc, String key) {
        Object val = doc.get(key);
        if (val == null) return null;
        if (val instanceof Double) return (Double) val;
        if (val instanceof Integer) return ((Integer) val).doubleValue();
        if (val instanceof Long) return ((Long) val).doubleValue();
        return null;
    }

    private Integer getInt(Document doc, String key) {
        Object val = doc.get(key);
        if (val == null) return null;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Double) return ((Double) val).intValue();
        if (val instanceof Long) return ((Long) val).intValue();
        return null;
    }
}
