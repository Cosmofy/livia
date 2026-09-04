import { Config } from 'stellate'

const config: Config = {
  config: {
    name: 'livia',
    originUrl: 'https://livia.arryan.xyz/graphql',
    mutationPolicy: 'List',
    rootTypeNames: { query: 'Query' },
    schemaPolling: { enabled: true },
    cacheIntrospection: true,
    partialQueryCaching: { enabled: true },
    nonCacheable: [
      'Query.apiKey',
      'Query.server',
      'Query.time',
      'Query.searchApods',
      'Query.news',
      'ApodSearchPayload',
      'ApodSearchResult',
      'NewsPage',
      'NewsArticle',
      'AuroraMeta',
      'AuroraLocation',
    ],
    rules: [
      // ============== REAL-TIME DATA (5 minutes) ==============
      {
        types: [
          'Aurora',
          'HpReading',
          'KpReading',
          'SolarWind',
          'AuroraOval',
          'FlareEvent',
          'SolarFlares',
          'SpaceWeather',
          'AuroraConditions',
          'AuroraPrediction',
          'HemisphericPower',
          'NearbyPrediction',
          'SolarWindReading',
        ],
        maxAge: 300,
        swr: 300,
        description: 'Aurora/space weather - real-time data, short cache',
      },

      // ============== NATURAL EVENTS (4 hours, purged every 4 hours) ==============
      {
        types: [
          'Event',
          'Source',
          'Category',
          'Geometry',
        ],
        maxAge: 14400,
        swr: 14400,
        description: 'Natural disaster events - purged every 4 hours',
      },

      // ============== LEGACY PICTURE OF THE DAY (48 hours, purged daily at 2am MT) ==============
      {
        types: [
          'Picture',
          'Explanation',
        ],
        maxAge: 172800,
        swr: 172800,
        description: 'Picture of the day - purged daily at 2am MT',
      },

      // ============== APOD (5 minutes; safe across Mountain Time midnight) ==============
      {
        types: ['Apod'],
        maxAge: 300,
        swr: 0,
        description: 'APOD REST facade - conservative TTL for the no-date current APOD query',
      },

      // ============== STATIC CONTENT (6 hours) ==============
      {
        types: [
          'Author',
          'Banner',
          'Webcam',
          'Article',
          'DarkZone',
          'ArticlePage',
          'LightPollution',
        ],
        maxAge: 21600,
        swr: 21600,
        description: 'Articles and reference data - rarely changes',
      },

      // ============== ASTRONOMY DATA (1 hour) ==============
      {
        types: [
          'MoonInfo',
          'SunImage',
          'SunTimes',
          'Astronomy',
          'SunImagery',
        ],
        maxAge: 3600,
        swr: 3600,
        description: 'Astronomy data - location dependent',
      },

      // ============== PLANETARY DATA (1 day) ==============
      {
        types: [
          'Planet',
          'Component',
          'Satellite',
          'DwarfPlanet',
          'OrbitalRadiation',
          'SatelliteElement',
          'SatelliteDistance',
          'SatelliteStructure',
          'SatelliteAtmosphere',
          'SatelliteComposition',
          'SatelliteExploration',
          'SatelliteTemperature',
          'SatelliteMagneticField',
          'SatelliteAtmosphereComponent',
        ],
        maxAge: 86400,
        swr: 86400,
        description: 'Planetary data',
      },

      // ============== UNIVERSE HIERARCHY (1 day) ==============
      {
        types: [
          'Star',
          'Galaxy',
          'Universe',
          'StarEnergy',
          'StarSystem',
          'StarDistance',
          'StarRotation',
          'Supercluster',
          'GalaxyCluster',
          'StarStructure',
          'StarComposition',
          'StarTemperature',
          'StarGalacticOrbit',
          'StarMagneticField',
          'StarSolarActivity',
        ],
        maxAge: 86400,
        swr: 86400,
        description: 'Universe hierarchy - extremely static, cache 1 day',
      },
    ],
  },
}

export default config
