"use client"

import type { AttachedFile } from "@/components/attachment-input"
import type { TimelineItem } from "@/features/dashboard/types"
// Explicit extensions keep this module importable from the plain `node --test`
// runner, which resolves neither the `@/` alias nor extensionless value imports.
import { sortTimelineItems } from "./session-utils.ts"
import { incomingTimelineItemCanReplace, mergeSequencedTimelineSnapshot } from "./timeline-sequence.ts"

export const OPTIMISTIC_ITEM_PREFIX = "optimistic-message:"

export function timelineClientMessageId(item: TimelineItem): string | null {
  const value = item.source.clientMessageId
  return typeof value === "string" ? value : null
}

export function isOptimisticTimelineItem(item: TimelineItem): boolean {
  return item.id.startsWith(OPTIMISTIC_ITEM_PREFIX) || item.source.optimistic === true
}

export function preserveOptimisticItems(baseItems: TimelineItem[], previousItems: TimelineItem[]): TimelineItem[] {
  const optimisticItems = previousItems.filter(isOptimisticTimelineItem)
  return mergeTimelineItems(optimisticItems, baseItems)
}

export function mergeTimelineSnapshot(
  currentItems: TimelineItem[],
  currentNextSeq: number,
  snapshotItems: TimelineItem[],
  snapshotNextSeq: number,
): { items: TimelineItem[]; nextSeq: number } {
  const merged = mergeSequencedTimelineSnapshot(
    currentItems,
    currentNextSeq,
    snapshotItems,
    snapshotNextSeq,
    isOptimisticTimelineItem,
  )
  return {
    ...merged,
    items: mergeTimelineItems(
      merged.items.filter(isOptimisticTimelineItem),
      merged.items.filter((item) => !isOptimisticTimelineItem(item)),
    ),
  }
}

export function mergeTimelineItems(
  currentItems: TimelineItem[],
  incomingItems: TimelineItem[],
): TimelineItem[] {
  const byId = new Map(currentItems.map((item) => [item.id, item]))
  for (const item of incomingItems) {
    let nextItem = item
    const clientMessageId = timelineClientMessageId(item)
    if (!isOptimisticTimelineItem(item)) {
      for (const [id, existing] of byId) {
        if (optimisticUserMessageMatchesServerItem(existing, item, clientMessageId)) {
          nextItem = mergeOptimisticAttachmentMetadata(item, existing)
          byId.delete(id)
        }
      }
    }
    const existing = byId.get(nextItem.id)
    if (!existing || incomingTimelineItemCanReplace(existing, nextItem)) {
      byId.set(nextItem.id, nextItem)
    }
  }
  return reconcileDshAssistantActivity(orderPendingLast(sortTimelineItems(Array.from(byId.values()))))
}

// A placeholder stays "in flight" until its echo reaches the client. A real user
// message sent from another device can carry a higher orderSeq, which would sort
// the pending placeholder up into the middle of the timeline and make it look
// settled, so pending placeholders are placed after the last user message. Agent
// replies keep their own position: they answer an earlier turn, not this one.
function orderPendingLast(items: TimelineItem[]): TimelineItem[] {
  const pending: TimelineItem[] = []
  const rest: TimelineItem[] = []
  for (const item of items) {
    if (isOptimisticTimelineItem(item) && item.status === "pending") pending.push(item)
    else rest.push(item)
  }
  if (pending.length === 0) return items
  let insertAt = 0
  for (const [index, item] of rest.entries()) {
    if (item.role === "user") insertAt = index + 1
  }
  return [...rest.slice(0, insertAt), ...pending, ...rest.slice(insertAt)]
}

function reconcileDshAssistantActivity(items: TimelineItem[]): TimelineItem[] {
  const itemIds = new Set(items.map((item) => item.id))
  const supersededIds = new Set<string>()

  for (const item of items) {
    const replacedBy = sourceString(item, "replacedBy")
    if (replacedBy && itemIds.has(replacedBy)) supersededIds.add(item.id)
  }

  for (let finalIndex = 0; finalIndex < items.length; finalIndex += 1) {
    const finalItem = items[finalIndex]
    if (!finalItem) continue
    if (!isDshAssistantMessage(finalItem) || finalItem.status !== "done") continue

    for (let candidateIndex = finalIndex - 1; candidateIndex >= 0; candidateIndex -= 1) {
      const candidate = items[candidateIndex]
      if (!candidate) continue
      if (candidate.type === "message" && candidate.role === "user") break
      if (!isDshAssistantMessage(candidate) || candidate.status !== "done") continue
      if (candidate.contentHash !== finalItem.contentHash) continue

      const candidateItemType = sourceString(candidate, "itemType")
      const finalItemType = sourceString(finalItem, "itemType")
      const isNativeReplacement = candidateItemType === "assistant_activity" && finalItemType === "message"
      const isLegacyReplacement = candidateItemType === null
        && finalItemType === null
        && candidate.revision > 1
        && finalItem.revision === 1
      if (!isNativeReplacement && !isLegacyReplacement) continue

      supersededIds.add(candidate.id)
      break
    }
  }

  return supersededIds.size > 0 ? items.filter((item) => !supersededIds.has(item.id)) : items
}

function isDshAssistantMessage(item: TimelineItem): boolean {
  return item.type === "message" && item.role === "assistant" && sourceString(item, "runtime") === "dsh"
}

function sourceString(item: TimelineItem, key: string): string | null {
  const value = item.source[key]
  return typeof value === "string" && value.length > 0 ? value : null
}

function optimisticUserMessageMatchesServerItem(
  optimisticItem: TimelineItem,
  serverItem: TimelineItem,
  serverClientMessageId: string | null,
): boolean {
  if (!isOptimisticTimelineItem(optimisticItem)) return false
  if (optimisticItem.type !== "message" || optimisticItem.role !== "user") return false
  if (serverItem.type !== "message" || serverItem.role !== "user") return false
  if (optimisticItem.status !== "pending") return false
  const optimisticClientMessageId = timelineClientMessageId(optimisticItem)
  if (serverClientMessageId && optimisticClientMessageId === serverClientMessageId) return true
  // A runtime that echoes no client message id (the ZCode database adapter is one)
  // leaves the placeholder unmatched, and a placeholder pinned as "pending" keeps
  // the send spinner on forever. The placeholder is appended at the tail of the
  // timeline, so a real user message that sorts after it proves the send already
  // landed in the session. The text comparison guards the rare case where the local
  // order guess was inflated above the real echo's order: a different real user
  // message (for example one sent on another device) must never be mistaken for
  // this placeholder's echo.
  return serverItem.orderSeq > optimisticItem.orderSeq
    && messageTextOf(serverItem) === messageTextOf(optimisticItem)
}

function messageTextOf(item: TimelineItem): string {
  const value = item.content?.text
  return typeof value === "string" ? value : ""
}

function mergeOptimisticAttachmentMetadata(serverItem: TimelineItem, optimisticItem: TimelineItem): TimelineItem {
  const serverAttachments = attachmentsFromContent(serverItem.content)
  const optimisticAttachments = attachmentsFromContent(optimisticItem.content)
  if (serverAttachments.length === 0 && optimisticAttachments.length === 0) return serverItem

  const optimisticByFileId = new Map(
    optimisticAttachments.flatMap((attachment) =>
      typeof attachment.fileId === "string" ? [[attachment.fileId, attachment] as const] : [],
    ),
  )
  const nextAttachments = (serverAttachments.length > 0 ? serverAttachments : optimisticAttachments).map((attachment, index) => {
    const optimistic = typeof attachment.fileId === "string"
      ? optimisticByFileId.get(attachment.fileId) ?? optimisticAttachments[index]
      : optimisticAttachments[index]
    if (!optimistic || typeof optimistic !== "object") return attachment
    const optimisticPreviewUrl = optimistic.previewUrl
    return {
      ...optimistic,
      ...attachment,
      name: attachment.name ?? optimistic.name,
      size: attachment.size ?? optimistic.size,
      mediaType: attachment.mediaType ?? optimistic.mediaType,
      previewUrl: attachment.previewUrl ?? optimisticPreviewUrl,
      optimistic: attachment.optimistic === true,
    }
  })

  return {
    ...serverItem,
    content: {
      ...serverItem.content,
      attachments: nextAttachments,
    },
  }
}

function attachmentsFromContent(content: TimelineItem["content"]): Array<Record<string, unknown>> {
  const raw = content.attachments
  if (!Array.isArray(raw)) return []
  return raw.filter((entry): entry is Record<string, unknown> => Boolean(entry) && typeof entry === "object")
}

export function buildOptimisticUserMessage({
  sessionId,
  clientMessageId,
  text,
  attachments,
  items,
  nextSeq,
}: {
  sessionId: string
  clientMessageId: string
  text: string
  attachments: AttachedFile[]
  items: TimelineItem[]
  nextSeq: number
}): TimelineItem {
  const now = new Date().toISOString()
  const lastOrderSeq = items.reduce((max, item) => Math.max(max, item.orderSeq), 0)
  const orderSeq = Math.max(lastOrderSeq + 1, nextSeq + 1)
  const optimisticAttachments = attachments.map((attachment) => ({
    fileId: attachment.uploaded?.fileId ?? `optimistic:${attachment.id}`,
    name: attachment.uploaded?.name ?? attachment.name,
    size: attachment.uploaded?.size ?? attachment.size,
    mediaType: attachment.uploaded?.mediaType ?? attachment.mediaType,
    openUrl: attachment.uploaded?.openUrl,
    downloadUrl: attachment.uploaded?.downloadUrl,
    previewUrl: attachment.type === "image" ? attachment.preview : undefined,
    optimistic: true,
  }))
  return {
    id: `${OPTIMISTIC_ITEM_PREFIX}${clientMessageId}`,
    sessionId,
    type: "message",
    status: "pending",
    role: "user",
    content: optimisticAttachments.length > 0 ? { text, attachments: optimisticAttachments } : { text },
    source: { clientMessageId, optimistic: true },
    orderSeq,
    revision: 0,
    contentHash: clientMessageId,
    updatedSeq: orderSeq,
    createdAt: now,
    updatedAt: now,
    completedAt: null,
  }
}

export function withServerAttachments(
  item: TimelineItem,
  attachments: Array<Record<string, unknown>>,
): TimelineItem {
  if (attachments.length === 0) return item
  const optimisticAttachments = attachmentsFromContent(item.content)
  const optimisticByFileId = new Map(
    optimisticAttachments.flatMap((attachment) =>
      typeof attachment.fileId === "string" ? [[attachment.fileId, attachment] as const] : [],
    ),
  )
  const nextAttachments = attachments.map((attachment, index) => {
    const optimistic = typeof attachment.fileId === "string"
      ? optimisticByFileId.get(attachment.fileId) ?? optimisticAttachments[index]
      : optimisticAttachments[index]
    return {
      ...optimistic,
      ...attachment,
      previewUrl: attachment.previewUrl ?? optimistic?.previewUrl,
      optimistic: false,
    }
  })
  return {
    ...item,
    content: {
      ...item.content,
      attachments: nextAttachments,
    },
  }
}

export function revokeOptimisticItemResources(item: TimelineItem): void {
  for (const attachment of attachmentsFromContent(item.content)) {
    revokeOptimisticAttachmentPreview(attachment)
  }
}

function revokeOptimisticAttachmentPreview(attachment: Record<string, unknown>): void {
  const previewUrl = attachment.previewUrl
  if (typeof previewUrl !== "string" || !previewUrl.startsWith("blob:")) return
  URL.revokeObjectURL(previewUrl)
}

export function markOptimisticItemFailed(item: TimelineItem, message: string): TimelineItem {
  return {
    ...item,
    status: "failed",
    content: { ...item.content, error: message },
    updatedAt: new Date().toISOString(),
  }
}
