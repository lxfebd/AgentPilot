import assert from "node:assert/strict"
import test from "node:test"

import { mergeTimelineItems } from "../src/components/session/optimistic-timeline.ts"

// DSH 先发一条瞬态 assistant_activity（例如"压缩中…"），再发最终 message。
// 契约 timeline-item 的 status 枚举含 done/failed/cancelled/interrupted ——
// 只要轮次终结，活动行就该退场。此前只认 done，导致失败轮次把活动行永久
// 留在时间线上，表现为「压缩中」刷屏不消失。
function dshAssistant(id, { status, itemType, hash, text, orderSeq }) {
  return {
    id,
    sessionId: "session",
    type: "message",
    status,
    role: "assistant",
    content: { text },
    // itemType 缺省视为 "message"：收拢判定要求 finalItemType === "message"。
    source: { runtime: "dsh", itemType: itemType ?? "message" },
    orderSeq,
    revision: 1,
    contentHash: hash,
    updatedSeq: orderSeq,
  }
}

const activity = (id, hash, options = {}) =>
  dshAssistant(id, { status: "done", itemType: "assistant_activity", hash, text: "压缩中…", orderSeq: 1, ...options })
const finalMessage = (id, status, hash, options = {}) =>
  dshAssistant(id, { status, hash, text: "Failed to authenticate. API Error: 401", orderSeq: 2, ...options })

function ids(items) {
  return items.map((item) => item.id)
}

test("a done message retires the preceding activity row", () => {
  assert.deepEqual(ids(mergeTimelineItems([], [activity("a1", "h1"), finalMessage("m1", "done", "h1")])), ["m1"])
})

test("a failed message retires the activity row too", () => {
  // 账号未登录时轮次失败：活动行不能继续留在时间线上。
  assert.deepEqual(ids(mergeTimelineItems([], [activity("a1", "h1"), finalMessage("m1", "failed", "h1")])), ["m1"])
})

test("cancelled and interrupted messages retire the activity row as well", () => {
  for (const status of ["cancelled", "interrupted"]) {
    assert.deepEqual(
      ids(mergeTimelineItems([], [activity("a1", "h1"), finalMessage("m1", status, "h1")])),
      ["m1"],
      status,
    )
  }
})

test("a still running message keeps the activity row visible", () => {
  assert.deepEqual(ids(mergeTimelineItems([], [activity("a1", "h1"), finalMessage("m1", "running", "h1")])), ["a1", "m1"])
})

test("an activity row is never retired by an unrelated hash", () => {
  assert.deepEqual(
    ids(mergeTimelineItems([], [activity("a1", "h1"), finalMessage("m1", "failed", "h2")])),
    ["a1", "m1"],
  )
})

test("a user message breaks the window so later equal hashes do not cross it", () => {
  const user = {
    id: "u1",
    sessionId: "session",
    type: "message",
    status: "done",
    role: "user",
    content: { text: "continue" },
    source: {},
    orderSeq: 1,
    revision: 1,
    contentHash: "u1",
    updatedSeq: 1,
  }
  assert.deepEqual(
    ids(mergeTimelineItems([], [activity("a1", "h1"), user, finalMessage("m1", "failed", "h1")])),
    ["a1", "u1", "m1"],
  )
})

test("non-dsh runtimes are left untouched", () => {
  const other = {
    ...activity("a1", "h1"),
    source: { runtime: "claude", itemType: "assistant_activity" },
  }
  assert.deepEqual(ids(mergeTimelineItems([], [other, finalMessage("m1", "failed", "h1")])), ["a1", "m1"])
})
