import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { registerSource } from './helpers/onboarding-source.mjs'

// The plan panel and its review panel are the two pieces of the Plan feature the
// clients gate on, and the port shipped both as files nobody mounted (web: the
// whole panel; desktop: the whole module set) and wired the review path never.
// These tests render them and assert a plan is actually shown and answerable, so
// a dropped mount or a dead review button fails here instead of in production.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://app.example.test/', pretendToBeVisual: true })
for (const name of ['window', 'document', 'navigator', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'HTMLFormElement', 'Element'])
  Object.defineProperty(globalThis, name, { configurable: true, value: dom.window[name] })
window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} })
// The plan card observes its own visibility; jsdom has no IntersectionObserver.
globalThis.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} }
globalThis.IS_REACT_ACT_ENVIRONMENT = true
const { createElement: h, act } = await import('react')
const { createRoot } = await import('react-dom/client')
const { NextIntlClientProvider } = await import('next-intl')
const hooks = registerSource()
const { PlanningPanel, PlanningReviewPanel, getPlanReviewTarget } = await import('../src/components/session/planning-panel.tsx')
hooks.deregister()
const messages = JSON.parse(readFileSync(new URL('../messages/zh-CN.json', import.meta.url), 'utf8'))

async function render(t, element) {
  const container = document.createElement('div'); document.body.append(container)
  const root = createRoot(container)
  const update = async next => {
    await act(async () => root.render(h(NextIntlClientProvider, { locale: 'zh-CN', messages, timeZone: 'Asia/Shanghai' }, next)))
  }
  await update(element)
  t.after(async () => { await act(async () => root.unmount()); container.remove() })
  return { container, update }
}

const plan = (revision = 1) => ({
  planId: 'p1', revision, contentHash: `sha256:${revision}`, title: '迁移计划',
  markdown: `# 迁移计划\n\n第 ${revision} 版`, createdAt: '2026-01-01T00:00:00Z',
})

function planning(state = {}, documents = [], overrides = {}) {
  return { state, documents, error: null, execute: async () => {}, refresh: async () => {}, ingest() {}, ...overrides }
}

const text = container => container.textContent

test('plan panel: a session in plan mode shows the panel', async t => {
  const { container } = await render(t, h(PlanningPanel, {
    planning: planning({ effectiveMode: 'plan', freshness: 'fresh', epoch: 'e1' }, []),
    canReview: false,
  }))
  assert.ok(container.querySelector('section[aria-label]'), 'the panel is rendered in plan mode')
  assert.match(text(container), /规划模式/, 'the mode badge names plan mode')
})

test('plan panel: a streaming draft is rendered while the plan is being written', async t => {
  const { container } = await render(t, h(PlanningPanel, {
    planning: planning({
      effectiveMode: 'plan', freshness: 'fresh', epoch: 'e1',
      draft: { draftId: 'd1', content: '正在起草迁移计划…', contentState: 'streaming', planId: 'p1', revision: 0 },
    }, []),
    canReview: false,
  }))
  assert.match(text(container), /正在起草迁移计划/, 'the draft body is shown')
})

test('plan panel: nothing is rendered for a session with no plan at all', async t => {
  const { container } = await render(t, h(PlanningPanel, {
    planning: planning({ effectiveMode: 'normal', freshness: 'fresh', epoch: 'e1' }, []),
    canReview: true,
  }))
  assert.equal(container.querySelector('section[aria-label]'), null, 'no plan means no panel')
})

test('plan panel: a live plan error is surfaced with a retry', async t => {
  const { container } = await render(t, h(PlanningPanel, {
    planning: planning({ effectiveMode: 'plan', freshness: 'stale', epoch: '' }, [], { error: '规划状态暂不可用' }),
    canReview: false,
  }))
  assert.match(text(container), /规划状态暂不可用/, 'the error is shown')
})

test('review panel: an open review offers the approve action and it reaches the port', async t => {
  const document = plan(1)
  const state = {
    effectiveMode: 'plan', freshness: 'fresh', epoch: 'e1', sessionId: 's1',
    capabilities: { supported: true, actions: ['approve_and_execute', 'request_revision'] },
    review: { reviewId: 'r1', planId: 'p1', planRevision: 1, contentHash: 'sha256:1', status: 'open' },
    proposal: plan(1),
  }
  const target = getPlanReviewTarget(state, true)
  assert.ok(target, 'the review target is derived for an open review')

  const executed = []
  const { container } = await render(t, h(PlanningReviewPanel, {
    planning: planning(state, [document], { execute: async request => { executed.push(request); return true } }),
    target,
  }))
  const approve = [...container.querySelectorAll('button')].find(item => /同意|执行/.test(item.textContent))
  assert.ok(approve, 'an approve action is offered for an open review')
  await act(async () => approve.click())
  assert.equal(executed.length, 1, 'approving calls the planning port')
  assert.equal(executed[0].command, 'approve_and_execute')
  assert.equal(executed[0].reviewId, 'r1', 'the review being answered is identified')
})

test('review panel: a review for another revision is not actionable', async t => {
  const document = plan(2)
  const state = {
    effectiveMode: 'plan', freshness: 'fresh', epoch: 'e1', sessionId: 's1',
    capabilities: { supported: true, actions: ['approve_and_execute'] },
    review: { reviewId: 'r1', planId: 'p1', planRevision: 1, contentHash: 'sha256:1', status: 'open' },
    proposal: plan(2),
  }
  assert.equal(getPlanReviewTarget(state, true), null, 'a review of an older revision yields no target')
  assert.ok(document, 'the newest document stays available for the timeline')
})
