import { test } from "node:test"
import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import path from "node:path"

import Ajv2020 from "ajv/dist/2020.js"

// contracts/protocol/1.0 ships fixtures that no consumer validated. These
// tests keep the fixtures honest: every valid fixture must satisfy its
// schema and every invalid fixture must be rejected by it.
const protocolDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../contracts/protocol/1.0",
)

const CASES = [
  { schema: "capability-set", valid: "known-and-extension.json", invalid: "negative-revision.json" },
  { schema: "event-envelope", valid: "extension-event.json", invalid: "missing-event-id.json" },
  { schema: "model-catalog", valid: "extension-metadata.json" },
  { schema: "permission-catalog", valid: "extension-metadata.json" },
]

async function readJson(...segments) {
  return JSON.parse(await readFile(path.join(protocolDir, ...segments), "utf8"))
}

async function compile(schemaName) {
  const schema = await readJson("schemas", `${schemaName}.schema.json`)
  // Contract schemas carry x-direction/x-protocol-version/x-pydantic-mode
  // annotations, so Ajv strict mode is off; validation semantics stay on.
  return new Ajv2020({ strict: false }).compile(schema)
}

for (const item of CASES) {
  test(`protocol fixture ${item.schema}/valid/${item.valid} validates`, async () => {
    const validate = await compile(item.schema)
    const fixture = await readJson("fixtures", "valid", item.schema, item.valid)
    assert.equal(validate(fixture), true, JSON.stringify(validate.errors, null, 2))
  })

  if (item.invalid) {
    test(`protocol fixture ${item.schema}/invalid/${item.invalid} is rejected`, async () => {
      const validate = await compile(item.schema)
      const fixture = await readJson("fixtures", "invalid", item.schema, item.invalid)
      assert.equal(validate(fixture), false)
    })
  }
}
