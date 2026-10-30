import assert from 'node:assert/strict'
import test from 'node:test'

import { snapshotFingerprint } from '../../src/host/dsh-runtime/snapshot-fingerprint.js'

test('snapshotFingerprint ignores object key order', () => {
  assert.equal(snapshotFingerprint({ a: 1, b: { c: 2, d: 3 } }), snapshotFingerprint({ b: { d: 3, c: 2 }, a: 1 }))
})

test('snapshotFingerprint changes when a value changes', () => {
  assert.notEqual(snapshotFingerprint({ revision: 1 }), snapshotFingerprint({ revision: 2 }))
})

test('snapshotFingerprint keeps array order significant', () => {
  assert.notEqual(snapshotFingerprint({ items: ['a', 'b'] }), snapshotFingerprint({ items: ['b', 'a'] }))
})

test('snapshotFingerprint drops undefined fields', () => {
  assert.equal(snapshotFingerprint({ a: 1, b: undefined }), snapshotFingerprint({ a: 1 }))
})

test('snapshotFingerprint returns an empty string for unsupported values', () => {
  const cyclic: Record<string, unknown> = {}
  cyclic.self = cyclic
  assert.equal(snapshotFingerprint(cyclic), '')
})
