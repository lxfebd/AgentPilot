import { jsonBytes } from './json-size.js'
import { randomUUID } from 'node:crypto'
import { receiptKey } from './attachments.js'
import { setTimeout as delay } from 'node:timers/promises'
import type { SessionId } from '@deepseek-ai/dsh-session'
import type { SessionLogSnapshot } from '@deepseek-ai/dsh-session-query'
import type { StreamChunk } from '@deepseek-ai/dsh-llm'
import { createProjection, replayHistory, PROJECTION_VERSION, type SessionProjection } from './history.js'
import { snapshotFingerprint } from './snapshot-fingerprint.js'
import { sessionId } from './identity.js'
import type { NativeChange, NativeRuntime } from './native.js'
import { record, type TimelineItem } from './types.js'
import { BridgeError } from './errors.js'
import type { SourceState } from './sessions/source.js'

export type SyncOperation = { kind: string, [key: string]: unknown }
export interface SyncBatch { streamId: string, batchSeq: number, projectionVersion: number, operations: SyncOperation[] }
export interface SyncCheckpoint { version: 1, projectionVersion: typeof PROJECTION_VERSION, throughSeq: number, historyHash: string, settled: boolean }

function matchesCheckpoint(value: unknown, projection: SessionProjection): boolean {
  const checkpoint = record(value)
  return checkpoint.version === 1 && checkpoint.projectionVersion === PROJECTION_VERSION && checkpoint.settled === true
    && projection.settled && checkpoint.throughSeq === projection.throughSeq && checkpoint.historyHash === projection.historyHash
}
const MAX_BUFFER = 10_000
const MAX_BYTES = 6 * 1024 * 1024
const MAX_QUEUED_BYTES = 16 * 1024 * 1024
/** Queue depth that triggers a mid-inventory drain on large workspaces. */
const INVENTORY_DRAIN_THRESHOLD = 2_000
/** Per-session changes that only say "reread this fact"; one queued copy suffices. */
const SESSION_INVALIDATIONS = new Set(['session', 'status', 'question', 'approval'])
/** Events with notifications or side effects that a recalibrating baseline does not repeat. */
const RETAINED_EVENTS = new Set(['turn/end', 'model/selection', 'agent-preset/selected'])
export const SYNC_FLUSH_MS = Math.ceil(1000 / 30)

class SyncTransportError extends Error {}

/** One ordered stream. Checkpoint-aware peers ingest history before ACK/save; page ACKs only confirm receipt. */
export class SyncFeed {
  readonly id = randomUUID()
  private batchSeq = 0
  private queue: NativeChange[] = []
  private queuedBytes = 0
  private queuedInvalidations = new Set<string>()
  private lastQueued = new Map<string, NativeChange>()
  private projections = new Map<string, SessionProjection>()
  private published = new Map<string, string>()
  private sourceAvailability = new Map<string, string>()
  private failedSessions = new Map<string, SourceState>()
  private capture: { id: string, snapshotId: string } | undefined
  private waitAck: { seq: number, resolve: () => void, reject: (error: Error) => void } | undefined
  private wake: (() => void) | undefined
  private closed = false
  private inventorying = false
  private unwatch: () => void
  private abort = new AbortController()
  private lastSentAt = -Infinity
  private bufferedOperations: SyncOperation[] | undefined
  private bufferedBytes = 0
  private receivedCheckpoint: unknown
  private checkpointDirty = new Set<string>()
  private emittedSnapshots = new Map<string, string>()

  constructor(private native: NativeRuntime, private namespace: string,
    private notify: (batch: SyncBatch) => void, private failed: (error: unknown) => void,
    private ackTimeoutMs = 60_000, private durableCheckpoints = false) {
    this.unwatch = native.watch(change => {
      if (this.closed) return
      // These events invalidate current facts; replaying every invalidation
      // cannot recover an intermediate value and can starve the initial scan.
      // Session-scoped invalidations collapse per session too: one queued copy
      // of "reread this fact" is enough no matter how often native repeats it.
      const invalidation = !('id' in change) ? (change.type === 'catalogs' ? `catalogs:${change.catalogType}` : change.type)
        : SESSION_INVALIDATIONS.has(change.type) ? `${change.type}:${change.id}` : undefined
      if (invalidation !== undefined) {
        if (this.queuedInvalidations.has(invalidation)) return
        this.queuedInvalidations.add(invalidation)
      }
      let bytes: number
      try { bytes = jsonBytes(change) }
      catch (error) {
        this.native.diagnostics.log('error', 'sync.event_failed', { streamId: this.id, sessionId: 'id' in change ? change.id : undefined }, error)
        if (!('id' in change)) return
        change = { type: 'refresh', id: change.id }
        bytes = jsonBytes(change)
      }
      this.queuedBytes += bytes
      if (change.type === 'stream' && this.coalesceStream(change)) { this.wake?.(); this.wake = undefined; return }
      // Overflow is a throughput problem, not a fatal one: compact the backlog
      // so the feed keeps its stream (and every runtime stays "running") instead
      // of failing and forcing a full resubscribe everywhere.
      if (this.queue.length >= MAX_BUFFER || this.queuedBytes > MAX_QUEUED_BYTES) {
        if (!this.compactQueue()) { this.fail(new Error('DSH event buffer full; resubscribe for a fresh baseline')); return }
      }
      // A queued stream change may absorb later chunks, so never share the emitted object.
      const queued = change.type === 'stream' ? { ...change } : change
      this.queue.push(queued)
      if ('id' in queued) this.lastQueued.set(queued.id, queued)
      this.wake?.(); this.wake = undefined
    })
  }
  start(): void {
    this.native.diagnostics.log('info', 'sync.started', { streamId: this.id })
    void this.run().catch(error => { if (!this.closed) this.fail(error) })
  }
  private fail(error: unknown): void {
    this.native.diagnostics.log('error', 'sync.failed', { streamId: this.id, batchSeq: this.batchSeq, waitingAck: this.waitAck?.seq, queuedEvents: this.queue.length }, error)
    this.close(); this.failed(error)
  }
  ack(seq: number, checkpoint?: unknown): void {
    if (seq < 1 || seq > this.batchSeq || !Number.isSafeInteger(seq)) throw new BridgeError('INVALID_PARAMS', 'Invalid event acknowledgement.')
    if (this.waitAck?.seq === seq) {
      this.receivedCheckpoint = checkpoint
      this.native.diagnostics.log('debug', 'sync.ack', { streamId: this.id, batchSeq: seq, elapsedMs: Math.round(performance.now() - this.lastSentAt) })
      const pending = this.waitAck; this.waitAck = undefined; pending.resolve()
    }
  }
  close(): void {
    if (this.closed) return
    this.native.diagnostics.log('info', 'sync.stopped', { streamId: this.id, batchSeq: this.batchSeq, waitingAck: this.waitAck?.seq })
    this.closed = true
    this.abort.abort()
    this.unwatch()
    this.waitAck?.reject(new Error('DSH sync closed'))
    this.waitAck = undefined
    this.wake?.(); this.wake = undefined
    this.queue = []; this.queuedBytes = 0; this.projections.clear()
    this.queuedInvalidations.clear(); this.lastQueued.clear()
    this.failedSessions.clear()
    this.bufferedOperations = undefined; this.bufferedBytes = 0
    this.emittedSnapshots.clear()
  }
  private async send(operations: SyncOperation[]): Promise<void> {
    this.abort.signal.throwIfAborted()
    if (this.bufferedOperations) {
      const bytes = jsonBytes(operations)
      if (this.bufferedOperations.length && this.bufferedBytes + bytes > MAX_BYTES) await this.flushOperations()
      this.abort.signal.throwIfAborted()
      for (const operation of operations) {
        const previous = this.bufferedOperations!.at(-1)
        if (previous?.kind === 'notifications' && operation.kind === 'notifications') {
          (previous.notifications as unknown[]).push(...operation.notifications as unknown[])
        } else this.bufferedOperations!.push(operation)
      }
      this.bufferedBytes += bytes
      return
    }
    await this.transmit(operations)
  }
  private async flushOperations(): Promise<void> {
    const operations = this.bufferedOperations?.splice(0) ?? []
    this.bufferedBytes = 0
    if (operations.length) await this.transmit(operations)
  }
  private async transmit(operations: SyncOperation[]): Promise<void> {
    // Limit actual frames too: pages and status/tool notifications must not bypass the cadence.
    while (performance.now() - this.lastSentAt < SYNC_FLUSH_MS) {
      await delay(Math.ceil(SYNC_FLUSH_MS - (performance.now() - this.lastSentAt)), undefined, { signal: this.abort.signal })
    }
    this.abort.signal.throwIfAborted()
    const batch: SyncBatch = { streamId: this.id, batchSeq: ++this.batchSeq, projectionVersion: PROJECTION_VERSION, operations }
    if (jsonBytes(batch) > 7 * 1024 * 1024) throw new Error('DSH sync batch exceeds frame size')
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waitAck = undefined
        reject(new SyncTransportError('DSH event acknowledgement timed out'))
      }, this.ackTimeoutMs)
      timer.unref()
      this.waitAck = { seq: batch.batchSeq,
        resolve: () => { clearTimeout(timer); resolve() },
        reject: error => { clearTimeout(timer); reject(error) } }
      this.lastSentAt = performance.now()
      try {
        this.native.diagnostics.log('debug', 'sync.batch', { streamId: this.id, batchSeq: batch.batchSeq, operations: operations.length, kinds: operations.map(op => op.kind).join(',') })
        this.notify(batch)
      } catch (error) {
        clearTimeout(timer); this.waitAck = undefined
        reject(new SyncTransportError('DSH event delivery failed', { cause: error }))
      }
    })
  }
  private async notification(method: string, params: Record<string, unknown>): Promise<void> {
    await this.send([{ kind: 'notifications', notifications: [{ method, params }] }])
  }
  /** Suppress repeat full-snapshot notifications whose payload is unchanged. */
  private emitIfChanged(scope: string, payload: unknown): boolean {
    const fingerprint = snapshotFingerprint(payload)
    if (!fingerprint) return true
    if (this.emittedSnapshots.get(scope) === fingerprint) return false
    this.emittedSnapshots.set(scope, fingerprint)
    return true
  }
  private async items(kind: string, id: string, items: TimelineItem[], snapshotId?: string): Promise<void> {
    let page: TimelineItem[] = [], bytes = 0
    const sendPage = async () => {
      if (snapshotId) await this.send([{ kind, sessionId: sessionId(this.namespace, id), items: page, snapshotId }])
      else await this.send([{ kind: 'notifications', notifications: page.map(item => ({
        method: 'timeline.itemUpsert', params: { sessionId: item.sessionId, externalSessionId: id, item },
      })) }])
    }
    for (const item of contentItems(items)) {
      const size = jsonBytes(item)
      if (size > MAX_BYTES) throw new Error('A DSH Timeline item exceeds the transport limit')
      if (page.length && (bytes + size > MAX_BYTES || page.length >= 250)) {
        if (!await this.native.visible(id)) return
        await sendPage()
        page = []; bytes = 0
      }
      page.push(item); bytes += size
    }
    if (page.length && await this.native.visible(id)) await sendPage()
  }
  private async baseline(id: string, restore = false): Promise<void> {
    await this.sessionOperation(id, 'snapshot', () => this.publishBaseline(id, restore))
  }
  private async publishBaseline(id: string, restore: boolean): Promise<void> {
    if (!await this.native.visible(id)) { await this.unavailable(id); return }
    const start = performance.now()
    let log: Awaited<ReturnType<NativeRuntime['read']>>
    try { log = await this.native.read(id as SessionId) }
    catch (error) {
      // Only native reads are isolated here. Transport/ACK failures below still
      // close the stream so a reconnect can recalibrate unacknowledged data.
      if (this.closed || error instanceof Error && error.name === 'AbortError') throw error
      await this.unavailable(id)
      return
    }
    const platformId = sessionId(this.namespace, id)
    const projection = createProjection(id, platformId, this.durableCheckpoints)
    let resumed = false
    if (restore && this.durableCheckpoints) {
      await this.send([{ kind: 'checkpoint.load', externalSessionId: id }])
      const checkpoint = record(this.receivedCheckpoint)
      if (Number.isSafeInteger(checkpoint.throughSeq) && Number(checkpoint.throughSeq) >= -1) {
        await replayHistory(projection, log, this.abort.signal, Number(checkpoint.throughSeq))
        resumed = matchesCheckpoint(checkpoint, projection)
        if (resumed) projection.drain()
      }
    }
    await replayHistory(projection, log, this.abort.signal)
    const title = log.events.findLast(event => event.type === 'session/title')
    const meta = { externalSessionId: id, title: title?.type === 'session/title' ? title.data.title : null,
      sourceState: await this.native.source.state(id), cwd: log.session.cwd ?? null,
      lastActivityAt: new Date(log.events.at(-1)?.time ?? log.session.createdAt).toISOString() }
    if (resumed) {
      const delta = projection.drain()
      if (!delta.removed.length && await this.native.visible(id)) {
        this.projections.set(id, projection)
        this.published.set(id, platformId)
        this.sourceAvailability.set(id, 'available')
        await this.notification('session.meta.upsert', { sessionId: platformId, ...meta })
        await this.items('timeline.upsert', id, delta.items)
        this.checkpointDirty.add(id)
        await this.notices(id)
        await this.state(id, log)
        this.native.diagnostics.log('info', 'sync.checkpoint_restored', { streamId: this.id, sessionId: id })
        return
      }
    }
    const snapshotId = randomUUID()
    if (!await this.native.visible(id)) return
    this.native.diagnostics.log('debug', 'snapshot.started', { streamId: this.id, sessionId: id })
    this.capture = { id, snapshotId }
    await this.send([{ kind: 'snapshot.begin', sessionId: platformId, snapshotId,
      meta,
      throughSeq: projection.throughSeq }])
    const snapshotItems = contentItems(projection.snapshot())
    await this.items('snapshot.items', id, snapshotItems, snapshotId)
    if (!await this.native.visible(id)) {
      await this.send([{ kind: 'snapshot.abort', sessionId: platformId, snapshotId }]); this.capture = undefined; return
    }
    await this.send([{ kind: 'snapshot.commit', sessionId: platformId, snapshotId,
      totalItems: snapshotItems.length, throughSeq: projection.throughSeq }])
    this.capture = undefined
    this.failedSessions.delete(id)
    projection.drain()
    this.projections.set(id, projection)
    this.checkpointDirty.add(id)
    this.published.set(id, platformId)
    this.sourceAvailability.set(id, 'available')
    await this.notices(id)
    await this.state(id, log)
    this.native.diagnostics.log('info', 'snapshot.completed', { streamId: this.id, sessionId: id, items: snapshotItems.length, elapsedMs: Math.round(performance.now() - start) })
  }
  private async notices(id: string): Promise<void> {
    for (const notice of [...this.native.questions.notices(this.namespace, id), ...this.native.approvals.notices(this.namespace, id)]) await this.notification('notice.upsert', notice)
  }
  private async state(id: string, snapshot?: SessionLogSnapshot): Promise<void> {
    if (!this.published.has(id) || !await this.native.visible(id)) return
    const log = this.native.ctx.sessions.get(id as SessionId)
    const pending = new Set<string>()
    for (const e of log?.snapshotEvents() ?? []) {
      if (String(e.type) === 'approval/asked') pending.add(String(record(e.data).id))
      if (String(e.type) === 'approval/decided') pending.delete(String(record(e.data).id))
    }
    const last = log?.snapshotEvents().findLast(e => e.type === 'turn/end')
    const status = pending.size || this.native.questions.waiting(id) || this.native.approvals.waiting(id) ? 'waiting_approval' : this.native.status(id as SessionId)
      ?? (last?.type === 'turn/end' && last.data.reason.kind === 'error' ? 'error' : 'idle')
    let configuration: Awaited<ReturnType<NativeRuntime['configuration']['state']>>
    try {
      configuration = await this.native.diagnostics.measure('session.configuration_read', { sessionId: id }, async () => snapshot ? this.native.configuration.state(id as SessionId, snapshot) : (await this.native.stateFacts(id as SessionId)).configuration)
    } catch (error) {
      if (this.closed || error instanceof Error && error.name === 'AbortError') throw error
      this.native.source.markReadFailed(id)
      await this.unavailable(id)
      return
    }
    await this.notification('session.state.updated', { sessionId: this.published.get(id), externalSessionId: id, status, ...configuration })
  }
  private async reconcile(): Promise<void> {
    for (const id of this.native.candidates()) await this.sessionOperation(id, 'visibility', async () => {
      if (!await this.native.visible(id)) await this.unavailable(id)
    })
    // Recheck source availability; selecting a draft never makes it eligible for import.
    for (const id of this.native.candidates()) if (!this.published.has(id)) await this.baseline(id)
  }
  private async unavailable(id: string): Promise<void> {
    const source = await this.sourceState(id)
    // Preserve AA history; a failed read never publishes an empty snapshot.
    // Only feeds that imported this session may create live source updates.
    if (this.sourceAvailability.has(id) && this.sourceAvailability.get(id) !== source.availability) {
      await this.notification('session.source.updated', { sessionId: sessionId(this.namespace, id), externalSessionId: id,
        ...source, observationOrigin: 'event' })
      this.sourceAvailability.set(id, source.availability)
    }
    if (this.durableCheckpoints) await this.send([{ kind: 'checkpoint.delete', externalSessionId: id }])
    this.published.delete(id); this.projections.delete(id)
  }
  private async sourceState(id: string): Promise<SourceState> {
    const source = await this.native.source.state(id)
    return source.availability === 'available' ? this.failedSessions.get(id) ?? source : source
  }
  private async sessionOperation(id: string, phase: string, operation: () => Promise<void>): Promise<void> {
    try { await operation() }
    catch (error) {
      if (this.closed || error instanceof SyncTransportError || error instanceof Error && error.name === 'AbortError') throw error
      this.native.diagnostics.log('error', 'sync.session_failed', { streamId: this.id, sessionId: id, phase }, error)
      this.failedSessions.set(id, { availability: 'unavailable', reason: 'sync_failed', observedAt: new Date().toISOString() })
      if (this.capture?.id === id) {
        await this.send([{ kind: 'snapshot.abort', sessionId: sessionId(this.namespace, id), snapshotId: this.capture.snapshotId }])
        this.capture = undefined
      }
      await this.unavailable(id)
    }
  }
  private async runtimeOperation(phase: string, operation: () => Promise<void>): Promise<void> {
    try { await operation() }
    catch (error) {
      if (this.closed || error instanceof SyncTransportError || error instanceof Error && error.name === 'AbortError') throw error
      this.native.diagnostics.log('error', 'sync.runtime_update_failed', { streamId: this.id, phase }, error)
    }
  }
  private async changes(changes: NativeChange[]): Promise<void> {
    const touched = new Set<string>(), statuses = new Set<string>(), capabilities = new Set<string>()
    const ended: [string, Record<string, unknown>][] = []
    let reconcile = false
    for (const change of changes) {
      if (change.type === 'capabilities' || change.type === 'commands') {
        await this.runtimeOperation('capabilities', async () => {
          const payload = await this.native.capabilities()
          if (this.emitIfChanged('runtime:capability', payload)) await this.notification('runtime.capability.updated', payload)
        })
        for (const id of this.published.keys()) await this.sessionOperation(id, 'capabilities', async () => {
          const payload = await this.native.capabilities(this.published.get(id), id as SessionId)
          if (this.emitIfChanged(`session:capability:${id}`, payload)) await this.notification('session.capability.updated', payload)
        })
        continue
      }
      if (change.type === 'catalogs') {
        if (change.catalogType === 'model' && this.native.ctx.get('llm')) {
          await this.runtimeOperation('models', async () => {
            const payload = { ...await this.native.catalogs.models() }
            if (this.emitIfChanged('catalog:model', payload)) await this.notification('catalog.model.update', payload)
          })
        }
        if (change.catalogType === 'permission' && this.native.ctx.get('permissionPresets')) {
          await this.runtimeOperation('permissions', async () => {
            const payload = this.native.catalogs.permissions()
            if (this.emitIfChanged('catalog:permission', payload)) await this.notification('catalog.permission.update', payload)
          })
        }
        if (change.catalogType === 'model') {
          await this.runtimeOperation('capabilities', async () => {
            const payload = await this.native.capabilities()
            if (this.emitIfChanged('runtime:capability', payload)) await this.notification('runtime.capability.updated', payload)
          })
        }
        continue
      }
      if (change.type === 'visibility') { reconcile = true; continue }
      const { id } = change
      await this.sessionOperation(id, change.type, async () => {
        if (!await this.native.visible(id)) {
          if (this.published.has(id)) reconcile = true
          return
        }
        if (change.type === 'refresh') {
          // A `resume` refresh recalibrates from the session's committed checkpoint when
          // it still matches; anything else (and an unsettled projection, whose prior
          // notifications may carry drafts a checkpoint delta cannot remove) recaptures.
          await this.baseline(id, change.resume === true && (this.projections.get(id)?.settled ?? true)); return
        }
        if (!this.published.has(id)) await this.baseline(id)
        if (!this.published.has(id)) return
        if (change.type === 'question' || change.type === 'approval') await this.notices(id)
        if (change.type === 'stream') {
          this.projections.get(id)?.stream(change.turn, change.step, change.chunk, change.time, change.throughSeq)
          touched.add(id)
          return
        }
        if (change.type === 'event') {
          if (['model/selection', 'agent-preset/selected'].includes(change.event.type)) capabilities.add(id)
          if (change.event.type === 'turn/end') ended.push([id, {
            sessionId: this.published.get(id), externalSessionId: id,
            sourceObservedAt: new Date(change.event.time).toISOString(),
            outcome: change.event.data.reason.kind === 'error' ? 'failed'
              : ['aborted', 'interrupted'].includes(change.event.data.reason.kind) ? 'interrupted' : 'completed',
          }])
          if (['turn/start', 'turn/end', 'approval/asked', 'approval/decided', 'model/selection', 'request/header',
            'permission/preset', 'sandbox/mode', 'approval/policy', 'agent-preset/selected'].includes(change.event.type)) statuses.add(id)
          const projection = this.projections.get(id)
          if (!projection) { await this.baseline(id); return }
          this.projections.delete(id); this.projections.set(id, projection)
          if (Number(change.event.seq) <= projection.throughSeq) return
          const key = receiptKey(change.event)
          const receipt = key ? (await this.native.attachments.readReceipts(id))[key] : undefined
          try { projection.apply(change.event, receipt) } catch { await this.baseline(id); return }
          touched.add(id)
          this.checkpointDirty.add(id)
          if (change.event.type === 'session/title') await this.notification('session.meta.upsert', {
            sessionId: this.published.get(id), externalSessionId: id, title: change.event.data.title })
        } else statuses.add(id)
      })
    }
    for (const id of touched) await this.sessionOperation(id, 'timeline', async () => {
      const delta = this.projections.get(id)?.drain()
      if (!delta || !await this.native.visible(id)) return
      // Existing ingest has no item-delete notification. Reconcile that session
      // with its complete snapshot when native finalization discards a draft.
      if (delta.removed.length) await this.baseline(id)
      else await this.items('timeline.upsert', id, delta.items)
    })
    for (const [id, params] of ended) await this.sessionOperation(id, 'turnEnded', async () => {
      if (this.published.has(id) && await this.native.visible(id)) await this.notification('session.turnEnded', params)
    })
    for (const id of statuses) await this.sessionOperation(id, 'state', () => this.state(id))
    for (const id of capabilities) await this.sessionOperation(id, 'capabilities', async () => {
      if (this.published.has(id)) await this.notification('session.capability.updated', await this.native.capabilities(this.published.get(id), id as SessionId))
    })
    // The inventory pass baselines every candidate itself, so reconciling here would
    // only duplicate that work (and can recurse into the same baselines).
    if (reconcile && !this.inventorying) await this.reconcile()
  }
  private async run(): Promise<void> {
    await this.notification('session.inventory.begin', { scanToken: this.id })
    this.inventorying = true
    try {
      await this.native.inventory(this.abort.signal, async entry => {
        await this.baseline(entry.header.id, true)
        await this.rememberCheckpoints()
        // Baselining a large workspace takes minutes. Live changes keep arriving the
        // whole time and this loop is the only consumer, so drain as we go instead of
        // letting the queue reach MAX_BUFFER and fail the stream.
        await this.drainDuringInventory()
      })
    } finally { this.inventorying = false }
    await this.changes(this.takeChanges())
    await this.rememberCheckpoints()
    const sessions = []
    for (const id of this.native.candidates()) sessions.push({ sessionId: sessionId(this.namespace, id),
      externalSessionId: id, sourceState: await this.sourceState(id) })
    await this.notification('session.inventory.complete', { scanToken: this.id, complete: true,
      sessions })
    this.native.diagnostics.log('info', 'sync.inventory_completed', { streamId: this.id, sessions: this.published.size })
    while (!this.closed) {
      if (!this.queue.length) await new Promise<void>(resolve => { this.wake = resolve })
      if (this.closed) break
      // Collect all native changes in one bounded window; projection.drain()
      // keeps the latest revision of each item without dropping native deltas.
      await delay(SYNC_FLUSH_MS, undefined, { signal: this.abort.signal })
      this.bufferedOperations = []
      await this.changes(this.takeChanges())
      // Keep checkpoints behind their data in the same frame. The Connector
      // ingests the notifications before saving them, without another RTT per turn.
      await this.rememberCheckpoints()
      await this.flushOperations()
      this.bufferedOperations = undefined
    }
  }
  /** Apply queued live changes between inventory entries so the buffer stays bounded. */
  private async drainDuringInventory(): Promise<void> {
    if (this.queue.length < INVENTORY_DRAIN_THRESHOLD) return
    const pending = this.takeChanges()
    if (!pending.length) return
    // Batch transport work into a single frame per drain; per-notification transmits
    // would each pay the 30 Hz cadence and make the inventory crawl.
    this.bufferedOperations = []
    try { await this.changes(pending) }
    finally {
      await this.flushOperations()
      this.bufferedOperations = undefined
    }
  }
  /**
   * Collapse a saturated backlog to one `refresh` per session. A refresh re-baselines
   * the whole session, so the collapsed work strictly subsumes the queued deltas while
   * keeping the stream (and every runtime's "running" health) alive.
   */
  /**
   * Merges a chunk into the session's last queued change when that is a delta of
   * the same block. Projections concatenate these deltas, so one merged chunk
   * yields the same result while a long reply stays one queue entry.
   */
  private coalesceStream(change: Extract<NativeChange, { type: 'stream' }>): boolean {
    const previous = this.lastQueued.get(change.id)
    if (previous?.type !== 'stream' || previous.turn !== change.turn || previous.step !== change.step
      || previous.throughSeq !== change.throughSeq) return false
    const merged = mergeChunks(previous.chunk, change.chunk)
    if (!merged) return false
    previous.chunk = merged; previous.time = change.time
    return true
  }
  /**
   * Keeps the feed alive when native changes outpace delivery (for example a
   * running Agent while the startup scan uploads a long history). The sessions
   * holding most of the queue give their changes up for one recalibration;
   * events whose notifications a baseline does not repeat stay queued after it.
   */
  private compactQueue(): boolean {
    const sessions = new Map<string, { count: number, bytes: number }>()
    const kinds = new Map<string, number>()
    let length = this.queue.length, bytes = 0
    for (const change of this.queue) {
      const size = jsonBytes(change)
      bytes += size
      kinds.set(change.type, (kinds.get(change.type) ?? 0) + 1)
      if (!('id' in change)) continue
      const session = sessions.get(change.id) ?? { count: 0, bytes: 0 }
      session.count++; session.bytes += size
      sessions.set(change.id, session)
    }
    // Merged chunks are counted at their full size, so recount before dropping anything.
    if (length < MAX_BUFFER && bytes <= MAX_QUEUED_BYTES) { this.queuedBytes = bytes; return true }
    const byCount = length >= MAX_BUFFER
    const collapsed = new Set<string>()
    for (const [id, session] of [...sessions].sort(([, a], [, b]) => byCount ? b.count - a.count : b.bytes - a.bytes)) {
      if (length <= MAX_BUFFER / 2 && bytes <= MAX_QUEUED_BYTES / 2) break
      collapsed.add(id); length -= session.count - 1; bytes -= session.bytes
    }
    if (!collapsed.size) return false
    const kept: NativeChange[] = [], tails = new Map<string, NativeChange[]>()
    const interrupted = new Set<string>()
    for (const change of this.queue) {
      if (!('id' in change) || !collapsed.has(change.id)) { kept.push(change); continue }
      if (change.type === 'refresh' && change.interrupted) interrupted.add(change.id)
      if (change.type === 'event' && RETAINED_EVENTS.has(change.event.type)) {
        tails.set(change.id, [...tails.get(change.id) ?? [], change])
      }
    }
    for (const id of collapsed) {
      for (const type of SESSION_INVALIDATIONS) this.queuedInvalidations.delete(`${type}:${id}`)
      const refresh: NativeChange = { type: 'refresh', id, resume: true, ...interrupted.has(id) ? { interrupted: true } : {} }
      kept.push(refresh, ...tails.get(id) ?? [])
      this.lastQueued.set(id, kept.at(-1)!)
    }
    this.native.diagnostics.log('warn', 'sync.queue_compacted', { streamId: this.id, queuedEvents: this.queue.length,
      remainingEvents: kept.length, sessions: collapsed.size, kinds: [...kinds].map(([type, count]) => `${type}:${count}`).join(',') })
    this.queue = kept
    this.queuedBytes = kept.reduce((total, change) => total + jsonBytes(change), 0)
    return this.queue.length < MAX_BUFFER && this.queuedBytes <= MAX_QUEUED_BYTES
  }
  private async rememberCheckpoints(): Promise<void> {
    if (!this.durableCheckpoints) { this.checkpointDirty.clear(); return }
    for (const id of this.checkpointDirty) {
      const projection = this.projections.get(id)
      if (!projection || !this.published.has(id) || this.failedSessions.has(id)) continue
      const checkpoint: SyncCheckpoint = { version: 1, projectionVersion: PROJECTION_VERSION,
        throughSeq: projection.throughSeq, historyHash: projection.historyHash!, settled: projection.settled }
      await this.send([{ kind: 'checkpoint.save', externalSessionId: id, checkpoint }])
    }
    this.checkpointDirty.clear()
  }
  private takeChanges(): NativeChange[] { this.queuedBytes = 0; this.queuedInvalidations.clear(); this.lastQueued.clear(); return this.queue.splice(0) }

}

function contentItems(items: TimelineItem[]): TimelineItem[] {
  // Lifecycle belongs to session.state.updated / session.turnEnded in the platform.
  return items.filter(item => item.type !== 'turn.start' && item.type !== 'turn.end')
}

/** One chunk with the effect of applying `a` then `b`, or undefined when they are not deltas of one block. */
function mergeChunks(a: StreamChunk, b: StreamChunk): StreamChunk | undefined {
  if (!('index' in a) || !('index' in b) || a.index !== b.index) return undefined
  if ((a.type === 'text-delta' && b.type === 'text-delta') || (a.type === 'reasoning-delta' && b.type === 'reasoning-delta')) {
    return { ...a, text: a.text + b.text }
  }
  if (a.type === 'tool-call-delta' && b.type === 'tool-call-delta' && a.id === b.id) {
    const name = b.name ?? a.name
    return { ...a, ...name === undefined ? {} : { name }, argumentsDelta: a.argumentsDelta + b.argumentsDelta }
  }
  return undefined
}
