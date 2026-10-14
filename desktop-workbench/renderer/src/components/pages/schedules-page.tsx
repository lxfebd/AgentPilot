"use client"

import * as React from "react"
import {
  AlertTriangle,
  CalendarClock,
  Loader2,
  Plus,
  RefreshCw,
  Trash2,
} from "lucide-react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"

import { useAuth } from "@/components/auth/auth-context"
import { PageHeader } from "@/components/pages/page-header"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { LoadingState } from "@/components/loading-state"
import { ScrollArea } from "@/components/ui/scroll-area"
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useWorkspace } from "@/components/workspace-context"
import { dashboardApi } from "@/features/dashboard/api"
import type { ScheduleView } from "@/features/dashboard/types"
import {
  buildScheduleCreateRequest,
  buildScheduleEnabledPatch,
  browserTimezone,
  deriveScheduleFailure,
  emptyScheduleDraft,
  formatNextRun,
  formatTriggerSummary,
  isValidWeekday,
  scheduleHasFinished,
  sortSchedules,
  validateScheduleForm,
  WEEKDAY_LABELS,
  type ScheduleFormDraft,
  type ScheduleTargetMode,
} from "@/features/schedules/schedule-presentation"

const WEEKDAY_KEYS = [
  "weekday.monday",
  "weekday.tuesday",
  "weekday.wednesday",
  "weekday.thursday",
  "weekday.friday",
  "weekday.saturday",
  "weekday.sunday",
] as const

const KIND_LABEL_KEYS = {
  once: "kind.once",
  daily: "kind.daily",
  weekly: "kind.weekly",
} as const

/** Translation key for a server weekday index (0 = Monday). */
function weekdayLabelKey(weekday: number | null): string {
  if (!isValidWeekday(weekday)) return "weekday.monday"
  return WEEKDAY_KEYS[weekday] ?? "weekday.monday"
}

export function SchedulesPage() {
  const t = useTranslations("dashboard.schedules")
  const { navigate } = useWorkspace()
  const { session: authSession } = useAuth()
  const token = authSession?.accessToken ?? ""

  const [schedules, setSchedules] = React.useState<ScheduleView[]>([])
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState<string | null>(null)
  const [createOpen, setCreateOpen] = React.useState(false)
  const [deleteTarget, setDeleteTarget] = React.useState<ScheduleView | null>(null)
  const [busyScheduleId, setBusyScheduleId] = React.useState<string | null>(null)

  const load = React.useCallback(async () => {
    if (!token) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const response = await dashboardApi.listSchedules(token)
      setSchedules(sortSchedules(response.schedules))
    } catch (err) {
      setError(err instanceof Error ? err.message : t("loadFailed"))
    } finally {
      setLoading(false)
    }
  }, [token, t])

  React.useEffect(() => {
    void load()
  }, [load])

  const toggleSchedule = async (schedule: ScheduleView, enabled: boolean) => {
    // No global guard here: while one row's request is in flight the other
    // rows' switches are disabled (see `busy`), so a click cannot reach this
    // point. Silently returning instead would snap the switch back with no
    // feedback, which looks like the app ignoring the user.
    if (!token) return
    setBusyScheduleId(schedule.id)
    try {
      const response = await dashboardApi.patchSchedule(
        token,
        schedule.id,
        buildScheduleEnabledPatch(enabled),
      )
      setSchedules((current) =>
        sortSchedules(current.map((item) => (item.id === schedule.id ? response.schedule : item))),
      )
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("updateFailed"))
    } finally {
      setBusyScheduleId(null)
    }
  }

  const removeSchedule = async () => {
    if (!token || !deleteTarget) return
    setBusyScheduleId(deleteTarget.id)
    try {
      await dashboardApi.deleteSchedule(token, deleteTarget.id)
      setSchedules((current) => current.filter((item) => item.id !== deleteTarget.id))
      setDeleteTarget(null)
      toast.success(t("deleted"))
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("deleteFailed"))
    } finally {
      setBusyScheduleId(null)
    }
  }

  const handleCreated = (schedule: ScheduleView) => {
    setCreateOpen(false)
    setSchedules((current) => sortSchedules([...current, schedule]))
  }

  return (
    <ScrollArea className="h-full min-h-0 w-full">
      <div className="mx-auto w-full max-w-3xl px-6 py-8">
        <PageHeader title={t("title")} description={t("description")} onBack={() => navigate("home")}>
          <Button type="button" variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw data-icon="inline-start" />
            {t("refresh")}
          </Button>
          <Button type="button" size="sm" onClick={() => setCreateOpen(true)} disabled={!token}>
            <Plus data-icon="inline-start" />
            {t("newSchedule")}
          </Button>
        </PageHeader>

        {loading ? (
          <LoadingState className="h-full" />
        ) : error ? (
          <p className="py-8 text-center text-sm text-destructive">{error}</p>
        ) : schedules.length === 0 ? (
          <p className="py-8 text-center text-sm text-muted-foreground">{t("empty")}</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {schedules.map((schedule) => (
              <ScheduleRow
                key={schedule.id}
                schedule={schedule}
                busy={busyScheduleId === schedule.id}
                anyBusy={busyScheduleId !== null}
                onToggle={(enabled) => void toggleSchedule(schedule, enabled)}
                onDelete={() => setDeleteTarget(schedule)}
                t={t}
              />
            ))}
          </ul>
        )}
      </div>

      {createOpen ? (
        <ScheduleCreateDialog
          token={token}
          onClose={() => setCreateOpen(false)}
          onCreated={handleCreated}
        />
      ) : null}

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => { if (!open) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("deleteDescription", { title: deleteTarget?.title ?? "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => void removeSchedule()}
              // Only this row's own delete blocks the button; an unrelated
              // row's toggle must not grey it out.
              disabled={busyScheduleId !== null && busyScheduleId === deleteTarget?.id}
            >
              {t("delete")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ScrollArea>
  )
}

function ScheduleRow({
  schedule,
  busy,
  anyBusy,
  onToggle,
  onDelete,
  t,
}: {
  schedule: ScheduleView
  /** This row's own request is in flight. */
  busy: boolean
  /** Some row's request is in flight — other rows must look disabled. */
  anyBusy: boolean
  onToggle: (enabled: boolean) => void
  onDelete: () => void
  t: ReturnType<typeof useTranslations>
}) {
  const timezone = schedule.timezone?.trim() || "UTC"
  const nextRun = formatNextRun(schedule, timezone)
  const finished = scheduleHasFinished(schedule)
  // The server's own report of a failed run, or null when the row is healthy.
  const failure = deriveScheduleFailure(schedule)
  // A schedule the server stopped is not "finished" and not "paused": its
  // target is gone, and `failure.reason` is the explanation.
  const switchedOff = Boolean(failure?.switchedOff)
  const summary = formatTriggerSummary(schedule, {
    once: t("kind.once"),
    daily: t("kind.daily"),
    weekly: t("kind.weekly"),
    weekdays: WEEKDAY_KEYS.map((key) => t(key)),
    timezoneSuffix: (zone) => `(${zone})`,
  })
  // Rendered only when `failure` exists; `undefined` so the tooltip attribute
  // is simply absent otherwise.
  const failureText = failure
    ? `${t("lastFailed")} · ${failure.reason}${failure.at ? ` · ${failure.at}` : ""}`
    : undefined

  return (
    <li className="flex min-h-14 items-center gap-3 rounded-lg px-2 py-2 transition-colors hover:bg-accent/40">
      {busy ? (
        <Loader2 className="size-3.5 shrink-0 animate-spin text-muted-foreground" />
      ) : (
        <CalendarClock className="size-4 shrink-0 text-muted-foreground" />
      )}
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 items-center gap-2">
          <p className="truncate text-sm font-medium">{schedule.title}</p>
          {switchedOff ? (
            <Badge variant="destructive" className="shrink-0 font-normal">{t("stoppedByServer")}</Badge>
          ) : finished ? (
            <Badge variant="secondary" className="shrink-0 font-normal">{t("finished")}</Badge>
          ) : !schedule.enabled ? (
            <Badge variant="outline" className="shrink-0 font-normal">{t("paused")}</Badge>
          ) : null}
        </div>
        <p className="mt-0.5 truncate text-xs text-muted-foreground" title={summary}>
          {summary}
        </p>
        {failure ? (
          <p className="mt-0.5 flex min-w-0 items-center gap-1.5 text-xs text-destructive" title={failureText}>
            <AlertTriangle className="size-3 shrink-0" />
            <span className="truncate">{failureText}</span>
          </p>
        ) : null}
      </div>
      <div className="shrink-0 text-right">
        <p className="text-xs text-muted-foreground">
          {nextRun
            ? `${t("nextRun")} ${nextRun}`
            : finished && !switchedOff
              ? t("finished")
              : t("notScheduled")}
        </p>
        <p className="truncate text-xs text-muted-foreground/70" title={schedule.prompt}>
          {schedule.sessionId ? t("existingSession") : t("newSession")}
        </p>
      </div>
      <Switch
        checked={schedule.enabled}
        onCheckedChange={onToggle}
        disabled={busy || anyBusy}
        aria-label={t("toggle", { title: schedule.title })}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="size-8 shrink-0 text-muted-foreground hover:text-destructive"
        onClick={onDelete}
        disabled={busy || anyBusy}
        aria-label={t("deleteSchedule", { title: schedule.title })}
      >
        <Trash2 />
      </Button>
    </li>
  )
}

function ScheduleCreateDialog({
  token,
  onClose,
  onCreated,
}: {
  token: string
  onClose: () => void
  onCreated: (schedule: ScheduleView) => void
}) {
  const t = useTranslations("dashboard.schedules")
  const tCommon = useTranslations("common")
  const { connectors, projects, sessions } = useWorkspace()
  const [draft, setDraft] = React.useState<ScheduleFormDraft>(() => emptyScheduleDraft(browserTimezone()))
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [saving, setSaving] = React.useState(false)

  const patch = React.useCallback((next: Partial<ScheduleFormDraft>) => {
    setDraft((current) => ({ ...current, ...next }))
    setErrors({})
  }, [])

  const submit = async () => {
    const found = validateScheduleForm(draft, new Date())
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      const response = await dashboardApi.createSchedule(token, buildScheduleCreateRequest(draft))
      toast.success(t("created"))
      onCreated(response.schedule)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : t("createFailed"))
    } finally {
      setSaving(false)
    }
  }

  const timezones = React.useMemo(() => scheduleTimezoneOptions(), [])

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("newSchedule")}</DialogTitle>
          <DialogDescription>{t("newScheduleDescription")}</DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field data-invalid={Boolean(errors.title)}>
            <FieldLabel htmlFor="schedule-title">{t("titleLabel")}</FieldLabel>
            <Input
              id="schedule-title"
              value={draft.title}
              onChange={(event) => patch({ title: event.target.value })}
              placeholder={t("titlePlaceholder")}
              aria-invalid={Boolean(errors.title)}
            />
            {errors.title ? <FieldError>{t(errors.title)}</FieldError> : null}
          </Field>

          <Field data-invalid={Boolean(errors.prompt)}>
            <FieldLabel htmlFor="schedule-prompt">{t("promptLabel")}</FieldLabel>
            <Textarea
              id="schedule-prompt"
              value={draft.prompt}
              onChange={(event) => patch({ prompt: event.target.value })}
              placeholder={t("promptPlaceholder")}
              className="min-h-20"
              aria-invalid={Boolean(errors.prompt)}
            />
            {errors.prompt ? <FieldError>{t(errors.prompt)}</FieldError> : null}
          </Field>

          <Field>
            <FieldLabel>{t("kindLabel")}</FieldLabel>
            <ToggleGroup
              type="single"
              value={draft.scheduleKind}
              onValueChange={(value: string) => {
                if (value) patch({ scheduleKind: value as ScheduleFormDraft["scheduleKind"] })
              }}
              size="sm"
            >
              {(["once", "daily", "weekly"] as const).map((kind) => (
                <ToggleGroupItem key={kind} value={kind}>
                  {t(KIND_LABEL_KEYS[kind])}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </Field>

          {draft.scheduleKind === "once" ? (
            <Field data-invalid={Boolean(errors.runAt)}>
              <FieldLabel htmlFor="schedule-run-at">{t("runAtLabel")}</FieldLabel>
              <Input
                id="schedule-run-at"
                type="datetime-local"
                value={draft.runAt ?? ""}
                onChange={(event) => patch({ runAt: event.target.value || null })}
                aria-invalid={Boolean(errors.runAt)}
              />
              <FieldDescription>{t("runAtHint", { timezone: draft.timezone })}</FieldDescription>
              {errors.runAt ? <FieldError>{t(errors.runAt)}</FieldError> : null}
            </Field>
          ) : (
            <Field data-invalid={Boolean(errors.runTime)}>
              <FieldLabel htmlFor="schedule-run-time">{t("runTimeLabel")}</FieldLabel>
              <Input
                id="schedule-run-time"
                type="time"
                value={draft.runTime ?? ""}
                onChange={(event) => patch({ runTime: event.target.value || null })}
                aria-invalid={Boolean(errors.runTime)}
              />
              <FieldDescription>{t("runTimeHint", { timezone: draft.timezone })}</FieldDescription>
              {errors.runTime ? <FieldError>{t(errors.runTime)}</FieldError> : null}
            </Field>
          )}

          {draft.scheduleKind === "weekly" ? (
            <Field data-invalid={Boolean(errors.weekday)}>
              <FieldLabel htmlFor="schedule-weekday">{t("weekdayLabel")}</FieldLabel>
              <NativeSelect
                id="schedule-weekday"
                value={draft.weekday === null ? "" : String(draft.weekday)}
                onChange={(event) =>
                  patch({ weekday: event.target.value === "" ? null : Number(event.target.value) })
                }
                aria-invalid={Boolean(errors.weekday)}
              >
                <NativeSelectOption value="">{t("weekdayPlaceholder")}</NativeSelectOption>
                {WEEKDAY_LABELS.map((label, index) => (
                  <NativeSelectOption key={label} value={String(index)}>
                    {t(weekdayLabelKey(index))}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
              {errors.weekday ? <FieldError>{t(errors.weekday)}</FieldError> : null}
            </Field>
          ) : null}

          <Field>
            <FieldLabel htmlFor="schedule-timezone">{t("timezoneLabel")}</FieldLabel>
            <NativeSelect
              id="schedule-timezone"
              value={draft.timezone}
              onChange={(event) => patch({ timezone: event.target.value })}
            >
              {timezones.map((zone) => (
                <NativeSelectOption key={zone} value={zone}>
                  {zone}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>

          <ScheduleTargetFields draft={draft} patch={patch} errors={errors} sessions={sessions} projects={projects} connectors={connectors} />
        </FieldGroup>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose} disabled={saving}>
            {tCommon("cancel")}
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={saving || !token}>
            {saving ? <Loader2 className="animate-spin" data-icon="inline-start" /> : null}
            {t("create")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ScheduleTargetFields({
  draft,
  patch,
  errors,
  sessions,
  projects,
  connectors,
}: {
  draft: ScheduleFormDraft
  patch: (next: Partial<ScheduleFormDraft>) => void
  errors: Record<string, string>
  sessions: ReturnType<typeof useWorkspace>["sessions"]
  projects: ReturnType<typeof useWorkspace>["projects"]
  connectors: ReturnType<typeof useWorkspace>["connectors"]
}) {
  const t = useTranslations("dashboard.schedules")
  // Explicit state, not derived from the id fields: clearing the picked project
  // must not silently flip the form back to the session tab.
  const targetMode = draft.targetMode

  const onlineConnectors = connectors.filter((connector) => connector.status === "online")
  const selectableProjects = projects.filter((project) =>
    onlineConnectors.some((connector) => connector.id === project.connectorId),
  )

  const switchTarget = (mode: ScheduleTargetMode) => {
    patch(
      mode === "session"
        ? { targetMode: mode, sessionId: null, projectId: null, connectorId: null }
        : { targetMode: mode, sessionId: null },
    )
  }

  return (
    <>
      <Field>
        <FieldLabel>{t("targetLabel")}</FieldLabel>
        <ToggleGroup
          type="single"
          value={targetMode}
          onValueChange={(value: string) => {
            if (value) switchTarget(value as ScheduleTargetMode)
          }}
          size="sm"
        >
          <ToggleGroupItem value="session">{t("targetExistingSession")}</ToggleGroupItem>
          <ToggleGroupItem value="project">{t("targetNewSession")}</ToggleGroupItem>
        </ToggleGroup>
        <FieldDescription>{t("targetHint")}</FieldDescription>
      </Field>

      {targetMode === "session" ? (
        <Field data-invalid={Boolean(errors.target)}>
          <FieldLabel htmlFor="schedule-session">{t("sessionLabel")}</FieldLabel>
          <NativeSelect
            id="schedule-session"
            value={draft.sessionId ?? ""}
            onChange={(event) => patch({ sessionId: event.target.value || null })}
            aria-invalid={Boolean(errors.target)}
          >
            <NativeSelectOption value="">{t("sessionPlaceholder")}</NativeSelectOption>
            {sessions.map((session) => (
              <NativeSelectOption key={session.id} value={session.id}>
                {session.title || t("untitledSession")}
              </NativeSelectOption>
            ))}
          </NativeSelect>
          {errors.target ? <FieldError>{t(errors.target)}</FieldError> : null}
        </Field>
      ) : (
        <>
          <Field data-invalid={Boolean(errors.target)}>
            <FieldLabel htmlFor="schedule-project">{t("projectLabel")}</FieldLabel>
            <NativeSelect
              id="schedule-project"
              value={draft.projectId ?? ""}
              onChange={(event) => patch({ projectId: event.target.value || null })}
              aria-invalid={Boolean(errors.target)}
            >
              <NativeSelectOption value="">{t("projectPlaceholder")}</NativeSelectOption>
              {selectableProjects.map((project) => (
                <NativeSelectOption key={project.id} value={project.id}>
                  {project.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field data-invalid={Boolean(errors.target)}>
            <FieldLabel htmlFor="schedule-connector">{t("connectorLabel")}</FieldLabel>
            <NativeSelect
              id="schedule-connector"
              value={draft.connectorId ?? ""}
              onChange={(event) => patch({ connectorId: event.target.value || null })}
              aria-invalid={Boolean(errors.target)}
            >
              <NativeSelectOption value="">{t("connectorPlaceholder")}</NativeSelectOption>
              {onlineConnectors.map((connector) => (
                <NativeSelectOption key={connector.id} value={connector.id}>
                  {connector.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
            {errors.target ? <FieldError>{t(errors.target)}</FieldError> : null}
          </Field>
        </>
      )}
    </>
  )
}

/**
 * IANA zones the user can pick, always led by the browser zone so the default
 * stays visible without hardcoding any region.
 */
function scheduleTimezoneOptions(): string[] {
  const browserZone = browserTimezone()
  const fallback = ["UTC", "America/New_York", "Europe/Berlin", "Asia/Shanghai", "Asia/Tokyo"]
  const options = [browserZone, ...fallback].filter(Boolean)
  return [...new Set(options)]
}
