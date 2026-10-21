import assert from "node:assert/strict"
import test from "node:test"

import { buildOptimisticUserMessage, mergeTimelineItems } from "../src/components/session/optimistic-timeline.ts"

function serverUserMessage(id, { orderSeq, clientMessageId = null, text = "echo" } = {}) {
  return {
    id,
    sessionId: "session",
    type: "message",
    status: "done",
    role: "user",
    content: { text },
    source: clientMessageId ? { clientMessageId } : {},
    orderSeq,
    revision: 1,
    contentHash: id,
    updatedSeq: orderSeq,
  }
}

function optimistic(clientMessageId, orderSeq) {
  return buildOptimisticUserMessage({
    sessionId: "session",
    clientMessageId,
    text: "sent from this client",
    attachments: [],
    items: [],
    nextSeq: orderSeq - 1,
  })
}

test("a server echo tagged with the client message id retires the placeholder", () => {
  const placeholder = optimistic("opt_1", 10)

  const merged = mergeTimelineItems([placeholder], [serverUserMessage("server-1", { orderSeq: 10, clientMessageId: "opt_1" })])

  assert.deepEqual(merged.map((item) => item.id), ["server-1"])
})

test("a newer real user message with the same text retires an unmatched placeholder", () => {
  const placeholder = optimistic("opt_1", 10)

  const merged = mergeTimelineItems([placeholder], [serverUserMessage("server-1", { orderSeq: 50, text: "sent from this client" })])

  assert.deepEqual(merged.map((item) => item.id), ["server-1"])
})

test("a newer real user message with different text keeps the placeholder pending", () => {
  const placeholder = optimistic("opt_1", 10)

  const merged = mergeTimelineItems([placeholder], [serverUserMessage("server-1", { orderSeq: 50, text: "a message from the desktop" })])

  assert.deepEqual(
    merged.map((item) => item.id),
    ["server-1", "optimistic-message:opt_1"],
  )
})

test("an older real user message keeps the placeholder pending", () => {
  const placeholder = optimistic("opt_1", 10)

  const merged = mergeTimelineItems([placeholder], [serverUserMessage("server-1", { orderSeq: 5 })])

  assert.deepEqual(
    merged.map((item) => item.id),
    ["server-1", "optimistic-message:opt_1"],
  )
})

test("an agent message never retires a placeholder", () => {
  const placeholder = optimistic("opt_1", 10)
  const answer = {
    ...serverUserMessage("server-agent", { orderSeq: 50 }),
    role: "assistant",
  }

  const merged = mergeTimelineItems([placeholder], [answer])

  assert.deepEqual(
    merged.map((item) => item.id),
    ["optimistic-message:opt_1", "server-agent"],
  )
})

test("a retired placeholder carries its attachment preview onto the real message", () => {
  const withAttachment = buildOptimisticUserMessage({
    sessionId: "session",
    clientMessageId: "opt_file",
    text: "look at this",
    attachments: [
      {
        id: "att_1",
        name: "photo.png",
        size: 12,
        mediaType: "image/png",
        preview: "blob:preview",
        type: "image",
        uploaded: { fileId: "file_1", name: "photo.png", size: 12, mediaType: "image/png" },
      },
    ],
    items: [],
    nextSeq: 9,
  })
  const echo = {
    ...serverUserMessage("server-1", { orderSeq: 50 }),
    content: {
      text: "look at this",
      attachments: [{ fileId: "file_1", name: "photo.png", size: 12, mediaType: "image/png" }],
    },
  }

  const merged = mergeTimelineItems([withAttachment], [echo])

  assert.deepEqual(merged.map((item) => item.id), ["server-1"])
  assert.equal(merged[0].content.attachments[0].previewUrl, "blob:preview")
})
