import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { composeServices } from '@apollo/composition'
import { parse } from 'graphql'

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const schemaPath = resolve(projectDir, 'build/federation/livia.graphql')
const outputPath = resolve(projectDir, 'build/federation/supergraph.graphql')

let schema
try {
  schema = readFileSync(schemaPath, 'utf8')
} catch (error) {
  console.error(`Federation schema is missing; run ./gradlew assembleFederationSchema first (${error.code ?? 'read error'})`)
  process.exit(1)
}

const result = composeServices([
  {
    name: 'livia',
    url: 'http://127.0.0.1:2259/graphql',
    typeDefs: parse(schema),
  },
])

if (result.errors?.length) {
  for (const error of result.errors) {
    console.error(error.message)
  }
  process.exit(1)
}

if (!result.supergraphSdl) {
  console.error('Apollo composition returned no supergraph SDL')
  process.exit(1)
}

mkdirSync(dirname(outputPath), { recursive: true })
writeFileSync(outputPath, result.supergraphSdl, 'utf8')
console.log(`Federation composition succeeded: ${outputPath}`)
