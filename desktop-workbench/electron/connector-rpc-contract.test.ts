import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

/**
 * The Desktop hosts the Python Connector over stdio JSON-RPC, so the two sides
 * cannot share a compiler. The shared manifest in contracts/local-machine is the
 * single source of truth: this test checks the Desktop half, and
 * connector/tests/test_rpc_contract_manifest.py checks the Python half.
 */

const appRoot = path.resolve(__dirname, "../..");
const repoRoot = path.resolve(appRoot, "..");
const manifestPath = path.join(repoRoot, "contracts", "local-machine", "2.0", "rpc.json");
const supervisorSource = fs.readFileSync(path.join(appRoot, "electron", "connector-supervisor.ts"), "utf8");

type Manifest = {
  methods: string[];
  notifications: string[];
  hosts: Record<string, { methods: string[]; notifications?: string[] }>;
};

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8")) as Manifest;
const desktopHost = manifest.hosts["desktop-workbench"];

test("the Desktop declares its RPC surface in the shared manifest", () => {
  assert.ok(desktopHost, "contracts/local-machine/2.0/rpc.json has no desktop-workbench host entry");
  const called = [...supervisorSource.matchAll(/request\("(connector\.[A-Za-z]+)"/g)].map((match) => match[1]);
  assert.ok(called.length >= 6, `expected the supervisor to issue Connector RPC requests, found ${called.length}`);
  for (const method of called) {
    assert.ok(manifest.methods.includes(method), `${method} is missing from the Connector method table`);
    assert.ok(desktopHost.methods.includes(method), `${method} is not declared for desktop-workbench`);
  }
});

test("the Desktop ignores no declared notification and consumes no undeclared one", () => {
  for (const name of desktopHost.notifications ?? []) {
    assert.ok(manifest.notifications.includes(name), `${name} is not a declared Connector notification`);
    assert.ok(supervisorSource.includes(`"${name}"`), `${name} is declared but the supervisor never handles it`);
  }
});

test("the manifest matches the Connector handler table", (context) => {
  const cliPath = path.join(repoRoot, "connector", "connector", "cli.py");
  if (!fs.existsSync(cliPath)) {
    context.skip("connector/ is not checked out next to desktop-workbench");
    return;
  }
  const registered = [...fs.readFileSync(cliPath, "utf8").matchAll(/^\s+"(connector\.[A-Za-z]+)":/gm)].map((match) => match[1]);
  assert.deepEqual([...registered].sort(), [...manifest.methods].sort());
});
