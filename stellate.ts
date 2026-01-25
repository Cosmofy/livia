import { Config } from 'stellate'

const config: Config = {
  config: {
    name: 'cosmofy',
    originUrl: 'https://livia.arryan.xyz/graphql',
    partialQueryCaching: { enabled: true },
    nonCacheable: [
      'Query.apiKey',
      'Query.server',
      'Query.time',
      'AuroraMeta',
      'AuroraLocation',
    ],
    rules: [
      // ============== REAL-TIME DATA (5 minutes) ==============
      {
        types: [
          'Aurora',
          'AuroraPrediction',
          'AuroraConditions',
          'NearbyPrediction',
          'SpaceWeather',
          'SolarWind',
          'SolarWindReading',
          'HemisphericPower',
          'SolarFlares',
          'AuroraOval',
          'KpReading',
          'FlareEvent',
          'HpReading',
        ],
        maxAge: 300,
        swr: 300,
        description: 'Aurora/space weather - real-time data, short cache',
      },

      // ============== NATURAL EVENTS (4 hours, purged every 4 hours) ==============
      {
        types: [
          'Event',
          'Category',
          'Source',
          'Geometry',
        ],
        maxAge: 14400,
        swr: 14400,
        description: 'Natural disaster events - purged every 4 hours',
      },

      // ============== PICTURE OF THE DAY (48 hours, purged daily at 2am MT) ==============
      {
        types: [
          'Picture',
          'Explanation',
        ],
        maxAge: 172800,
        swr: 172800,
        description: 'Picture of the day - purged daily at 2am MT',
      },

      // ============== STATIC CONTENT (6 hours) ==============
      {
        types: [
          'Article',
          'Author',
          'Banner',
          'Webcam',
          'LightPollution',
          'DarkZone',
        ],
        maxAge: 21600,
        swr: 21600,
        description: 'Articles and reference data - rarely changes',
      },

      // ============== ASTRONOMY DATA (1 hour) ==============
      {
        types: [
          'Astronomy',
          'SunTimes',
          'MoonInfo',
          'SunImagery',
          'SunImage',
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
          'OrbitalRadiation',
          'Satellite',
          'SatelliteDistance',
          'SatelliteTemperature',
          'SatelliteStructure',
          'SatelliteComposition',
          'SatelliteElement',
          'SatelliteAtmosphere',
          'SatelliteAtmosphereComponent',
          'SatelliteMagneticField',
          'SatelliteExploration',
          'DwarfPlanet',
        ],
        maxAge: 86400,
        swr: 86400,
        description: 'Planetary data',
      },

      // ============== UNIVERSE HIERARCHY (1 day) ==============
      {
        types: [
          'Universe',
          'Supercluster',
          'GalaxyCluster',
          'Galaxy',
          'StarSystem',
          'Star',
          'StarDistance',
          'StarTemperature',
          'StarRotation',
          'StarStructure',
          'StarComposition',
          'StarEnergy',
          'StarMagneticField',
          'StarGalacticOrbit',
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
