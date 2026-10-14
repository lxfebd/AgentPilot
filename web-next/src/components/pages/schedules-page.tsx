"use client"

import * as React from "react"
import { CalendarClock, Plus, RefreshCw, Trash2 } from "lucide-react"
import { useTranslations } from "next-intl"
import { toast } from "sonner"

import { useAuth } from "@/components/auth/auth-context"
import { PageHeader } from "@/components/pages/page-header"
import { LoadingState } from "@/components/loading-state"
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
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Separator } from "@/components/ui/separator"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useWorkspace } from "@/components/workspace-context"
import { dashboardApi } from "@/features/dashboard/api"
import type {
  ScheduleKind,
  ScheduleView,
} from "@/features/dashboard/types"
import {
  SCHEDULE_KIND_ONCE,
  WEEKDAY_KEYS,
  browserTimezone,
  buildScheduleCreateRequest,
  describeScheduleTrigger,
  isScheduleKind,
  normalizeRunTime,
  runAtFromInput,
  scheduleNextRunLabel,
  validateScheduleDraft,
  type ScheduleDraft,
  type ScheduleSummaryLabels,
  type ScheduleTargetDraft,
  type ScheduleValidationResult,
} from "@/features/schedules/schedule-presentation"

const KIND_KEYS: Record<ScheduleKind, string> = {
  once: "kindOnce",
  daily: "kindDaily",
  weekly: "kindWeekly",
}

type TargetMode = ScheduleTargetDraft["mode"]

export function SchedulesPage() {
  const t = useTranslations("pages.schedules")
  const tCommon = useTranslations("common")
  const { navigate } = useWorkspace()
  const { session } = useAuth()
  const token = session?.accessToken

  const [schedules, setSchedules] = React.useState<ScheduleView[]>([])
  const [loading, setLoading] = React.useState(true)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [createOpen, setCreateOpen] = React.useState(false)
  const [deleteTarget, setDeleteTarget] = React.useState<ScheduleView | null>(null)
  // One flag per row, not a single slot: a shared slot is cleared by whichever
  // request finishes first, which silently un-disables a row whose request is
  // still in flight and lets a second PATCH race the first.
  const [busyIds, setBusyIds] = React.useState<Record<string, boolean>>({})

  const markBusy = (id: string, busy: boolean) => {
    setBusyIds((current) => {
      const next = { ...current }
      if (busy) next[id] = true
      else delete next[id]
      return next
    })
  }

  /** True only while the row being deleted has a request in flight, so an
   * unrelated row's toggle does not grey out the delete confirmation. */
  const deletingRow = deleteTarget ? Boolean(busyIds[deleteTarget.id]) : false

  const load = React.useCallback(async () => {
    // Without a token there is nothing to load — leaving `loading` true would
    // spin forever instead of showing the empty or error state.
    if (!token) {
      setLoading(false)
      return
    }
    setLoading(true)
    setLoadError(null)
    try {
      const response = await dashboardApi.listSchedules(token)
      setSchedules(response.schedules)
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : t("loadFailed"))
    } finally {
      setLoading(false)
    }
  }, [t, token])

  React.useEffect(() => {
    void load()
  }, [load])

  const toggleSchedule = async (schedule: ScheduleView, enabled: boolean) => {
    if (!token) return
    markBusy(schedule.id, true)
    try {
      const response = await dashboardApi.patchSchedule(token, schedule.id, { enabled })
      setSchedules((current) => current.map((item) => (item.id === schedule.id ? response.schedule : item)))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("toggleFailed"))
    } finally {
      markBusy(schedule.id, false)
    }
  }

  const removeSchedule = async (schedule: ScheduleView) => {
    if (!token) return
    markBusy(schedule.id, true)
    try {
      await dashboardApi.deleteSchedule(token, schedule.id)
      setSchedules((current) => current.filter((item) => item.id !== schedule.id))
      setDeleteTarget(null)
      toast.success(t("deleted"))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t("deleteFailed"))
    } finally {
      markBusy(schedule.id, false)
    }
  }

  const summaryLabels = React.useMemo(() => ({
    once: t("summaryOnce"),
    daily: t("summaryDaily"),
    weekly: t("summaryWeekly"),
    weekdays: WEEKDAY_KEYS.map((key) => t(`weekdays.${key}`)),
    timezoneSuffix: (timezone: string) => `(${timezone})`,
  }), [t])

  return (
    <ScrollArea className="h-full min-h-0 w-full">
      <div className="mx-auto w-full max-w-3xl px-6 pb-8 pt-14 @min-[52rem]/page:pt-8">
        <PageHeader title={t("title")} description={t("description")} onBack={() => navigate("home")}>
          <Button type="button" variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw data-icon="inline-start" />
            {loading ? t("loading") : t("refresh")}
          </Button>
          <Button type="button" size="sm" onClick={() => setCreateOpen(true)}>
            <Plus data-icon="inline-start" />
            {t("new")}
          </Button>
        </PageHeader>

        <Separator className="my-6" />

        {loading ? (
          <LoadingState className="min-h-24" />
        ) : loadError ? (
          <p className="py-4 text-sm text-destructive">{loadError}</p>
        ) : schedules.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center">
            <CalendarClock className="size-8 text-muted-foreground" />
            <p className="text-sm text-muted-foreground">{t("empty")}</p>
          </div>
        ) : (
          <ul className="flex flex-col gap-2">
            {schedules.map((schedule) => (
              <ScheduleRow
                key={schedule.id}
                schedule={schedule}
                summaryLabels={summaryLabels}
                busy={Boolean(busyIds[schedule.id])}
                onToggle={(enabled) => void toggleSchedule(schedule, enabled)}
                onDelete={() => setDeleteTarget(schedule)}
              />
            ))}
          </ul>
        )}
      </div>

      {createOpen ? (
        <ScheduleCreateDialog
          token={token ?? ""}
          onOpenChange={setCreateOpen}
          onCreated={(schedule) => {
            setSchedules((current) => [schedule, ...current])
            setCreateOpen(false)
          }}
        />
      ) : null}

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open: boolean) => {
        if (!open) setDeleteTarget(null)
      }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("deleteDescription", { title: deleteTarget?.title ?? "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingRow}>
              {tCommon("cancel")}
            </AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deletingRow}
              onClick={() => {
                if (deleteTarget) void removeSchedule(deleteTarget)
              }}
            >
              {deletingRow ? t("working") : t("delete")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ScrollArea>
  )
}

function ScheduleRow({
  schedule,
  summaryLabels,
  busy,
  onToggle,
  onDelete,
}: {
  schedule: ScheduleView
  summaryLabels: ScheduleSummaryLabels
  busy: boolean
  onToggle: (enabled: boolean) => void
  onDelete: () => void
}) {
  const t = useTranslations("pages.schedules")
  const summary = describeScheduleTrigger(schedule, summaryLabels)
  const nextRun = scheduleNextRunLabel(schedule, t("nextRunNone"))
  const targetLabel = schedule.sessionId
    ? t("targetExistingSession")
    : schedule.projectId
      ? t("targetNewSession")
      : t("targetUnknown")

  return (
    <li className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{schedule.title}</p>
        <p className="truncate text-xs text-muted-foreground">{summary}</p>
        <p className="truncate text-xs text-muted-foreground">
          {t("nextRun", { time: nextRun })} · {targetLabel}
        </p>
      </div>
      <Badge variant="outline" className="shrink-0 font-normal">
        {t(KIND_KEYS[schedule.scheduleKind] ?? "kindDaily")}
      </Badge>
      <Switch
        checked={schedule.enabled}
        onCheckedChange={onToggle}
        disabled={busy}
        aria-label={t("enableToggle", { title: schedule.title })}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="size-8 text-muted-foreground hover:text-destructive"
        onClick={onDelete}
        disabled={busy}
        aria-label={t("deleteSchedule", { title: schedule.title })}
      >
        <Trash2 />
      </Button>
    </li>
  )
}

type ScheduleFormDraft = {
  title: string
  prompt: string
  scheduleKind: ScheduleKind
  runAt: string
  runTime: string
  weekday: number
  timezone: string
  targetMode: TargetMode
  sessionId: string
  projectId: string
  connectorId: string
}

function initialDraft(): ScheduleFormDraft {
  return {
    title: "",
    prompt: "",
    scheduleKind: "daily",
    runAt: "",
    runTime: "09:00",
    weekday: 0,
    timezone: browserTimezone(),
    targetMode: "session",
    sessionId: "",
    projectId: "",
    connectorId: "",
  }
}

function toScheduleDraft(draft: ScheduleFormDraft): ScheduleDraft {
  return {
    title: draft.title,
    prompt: draft.prompt,
    scheduleKind: draft.scheduleKind,
    runAt: draft.scheduleKind === SCHEDULE_KIND_ONCE ? draft.runAt : null,
    runTime: draft.scheduleKind === SCHEDULE_KIND_ONCE ? null : draft.runTime,
    weekday: draft.scheduleKind === "weekly" ? draft.weekday : null,
    timezone: draft.timezone,
    target: draft.targetMode === "session"
      ? { mode: "session", sessionId: draft.sessionId }
      : { mode: "project", projectId: draft.projectId, connectorId: draft.connectorId },
  }
}

function ScheduleCreateDialog({
  token,
  onOpenChange,
  onCreated,
}: {
  token: string
  onOpenChange: (open: boolean) => void
  onCreated: (schedule: ScheduleView) => void
}) {
  const t = useTranslations("pages.schedules")
  const { connectors, projects } = useWorkspace()
  const [draft, setDraft] = React.useState<ScheduleFormDraft>(initialDraft)
  const [saving, setSaving] = React.useState(false)
  const [serverError, setServerError] = React.useState<string | null>(null)
  const [submitted, setSubmitted] = React.useState(false)

  const validation: ScheduleValidationResult = React.useMemo(
    () => validateScheduleDraft(toScheduleDraft(draft), {
      titleRequired: t("titleRequired"),
      promptRequired: t("promptRequired"),
      targetRequired: t("targetRequired"),
      runAtRequired: t("runAtRequired"),
      runAtFuture: t("runAtFuture"),
      runTimeRequired: t("runTimeRequired"),
      weekdayRequired: t("weekdayRequired"),
      timezoneInvalid: t("timezoneInvalid"),
    }),
    [draft, t],
  )
  const showError = submitted || serverError !== null
  const fieldError = (key: keyof ScheduleValidationResult["fieldErrors"]) =>
    showError ? validation.fieldErrors[key] : null

  // A local wall-clock `datetime-local` string has no zone attached, so the
  // timezone must be resolved at submit time rather than while typing.
  const submit = async () => {
    setSubmitted(true)
    if (!validation.ok) return
    const scheduleDraft = toScheduleDraft(draft)
    const request = buildScheduleCreateRequest({
      ...scheduleDraft,
      runAt: scheduleDraft.scheduleKind === SCHEDULE_KIND_ONCE
        ? runAtFromInput(draft.runAt, draft.timezone)
        : null,
      runTime: normalizeRunTime(draft.runTime),
    })
    setSaving(true)
    setServerError(null)
    try {
      const response = await dashboardApi.createSchedule(token, request)
      onCreated(response.schedule)
      toast.success(t("created"))
    } catch (error) {
      setServerError(error instanceof Error ? error.message : t("createFailed"))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("newTitle")}</DialogTitle>
          <DialogDescription>{t("newDescription")}</DialogDescription>
        </DialogHeader>

        <FieldGroup>
          <Field data-invalid={Boolean(fieldError("title"))}>
            <FieldLabel htmlFor="schedule-title">{t("titleLabel")}</FieldLabel>
            <Input
              id="schedule-title"
              value={draft.title}
              onChange={(event) => setDraft({ ...draft, title: event.currentTarget.value })}
              aria-invalid={Boolean(fieldError("title"))}
              autoFocus
            />
            <FieldError>{fieldError("title")}</FieldError>
          </Field>

          <Field data-invalid={Boolean(fieldError("prompt"))}>
            <FieldLabel htmlFor="schedule-prompt">{t("promptLabel")}</FieldLabel>
            <Textarea
              id="schedule-prompt"
              value={draft.prompt}
              onChange={(event) => setDraft({ ...draft, prompt: event.currentTarget.value })}
              aria-invalid={Boolean(fieldError("prompt"))}
            />
            <FieldError>{fieldError("prompt")}</FieldError>
          </Field>

          <Field>
            <FieldLabel>{t("kindLabel")}</FieldLabel>
            <ToggleGroup
              type="single"
              variant="outline"
              spacing={0}
              value={draft.scheduleKind}
              onValueChange={(value: string) => {
                if (isScheduleKind(value)) setDraft({ ...draft, scheduleKind: value })
              }}
            >
              <ToggleGroupItem value="once">{t("kindOnce")}</ToggleGroupItem>
              <ToggleGroupItem value="daily">{t("kindDaily")}</ToggleGroupItem>
              <ToggleGroupItem value="weekly">{t("kindWeekly")}</ToggleGroupItem>
            </ToggleGroup>
          </Field>

          {draft.scheduleKind === SCHEDULE_KIND_ONCE ? (
            <Field data-invalid={Boolean(fieldError("runAt"))}>
              <FieldLabel htmlFor="schedule-runAt">{t("runAtLabel")}</FieldLabel>
              <Input
                id="schedule-runAt"
                type="datetime-local"
                value={draft.runAt}
                onChange={(event) => setDraft({ ...draft, runAt: event.currentTarget.value })}
                aria-invalid={Boolean(fieldError("runAt"))}
              />
              <FieldDescription>{t("runAtDescription", { timezone: draft.timezone })}</FieldDescription>
              <FieldError>{fieldError("runAt")}</FieldError>
            </Field>
          ) : (
            <Field data-invalid={Boolean(fieldError("runTime"))}>
              <FieldLabel htmlFor="schedule-runTime">{t("runTimeLabel")}</FieldLabel>
              <Input
                id="schedule-runTime"
                type="time"
                value={draft.runTime}
                onChange={(event) => setDraft({ ...draft, runTime: event.currentTarget.value })}
                aria-invalid={Boolean(fieldError("runTime"))}
              />
              <FieldError>{fieldError("runTime")}</FieldError>
            </Field>
          )}

          {draft.scheduleKind === "weekly" ? (
            <Field data-invalid={Boolean(fieldError("weekday"))}>
              <FieldLabel>{t("weekdayLabel")}</FieldLabel>
              <ToggleGroup
                type="single"
                variant="outline"
                spacing={0}
                value={String(draft.weekday)}
                onValueChange={(value: string) => {
                  if (value) setDraft({ ...draft, weekday: Number(value) })
                }}
              >
                {WEEKDAY_KEYS.map((key, index) => (
                  <ToggleGroupItem key={key} value={String(index)}>
                    {t(`weekdaysShort.${key}`)}
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
              <FieldError>{fieldError("weekday")}</FieldError>
            </Field>
          ) : null}

          <Field data-invalid={Boolean(fieldError("timezone"))}>
            <FieldLabel htmlFor="schedule-timezone">{t("timezoneLabel")}</FieldLabel>
            <Input
              id="schedule-timezone"
              value={draft.timezone}
              onChange={(event) => setDraft({ ...draft, timezone: event.currentTarget.value })}
              aria-invalid={Boolean(fieldError("timezone"))}
              placeholder="Asia/Shanghai"
            />
            <FieldDescription>{t("timezoneDescription")}</FieldDescription>
            <FieldError>{fieldError("timezone")}</FieldError>
          </Field>

          <Separator />

          <Field data-invalid={Boolean(fieldError("target"))}>
            <FieldLabel>{t("targetLabel")}</FieldLabel>
            <ToggleGroup
              type="single"
              variant="outline"
              spacing={0}
              value={draft.targetMode}
              onValueChange={(value: string) => {
                if (value === "session" || value === "project") {
                  setDraft({ ...draft, targetMode: value })
                }
              }}
            >
              <ToggleGroupItem value="session">{t("targetExistingSession")}</ToggleGroupItem>
              <ToggleGroupItem value="project">{t("targetNewSession")}</ToggleGroupItem>
            </ToggleGroup>
            <FieldDescription>{t("targetDescription")}</FieldDescription>
          </Field>

          {draft.targetMode === "session" ? (
            <Field data-invalid={Boolean(fieldError("target"))}>
              <FieldLabel htmlFor="schedule-session">{t("sessionLabel")}</FieldLabel>
              <SessionPicker
                id="schedule-session"
                value={draft.sessionId}
                onChange={(sessionId) => setDraft({ ...draft, sessionId })}
              />
              <FieldError>{fieldError("target")}</FieldError>
            </Field>
          ) : (
            <>
              <Field data-invalid={Boolean(fieldError("target"))}>
                <FieldLabel htmlFor="schedule-project">{t("projectLabel")}</FieldLabel>
                <select
                  id="schedule-project"
                  className="h-8 w-full rounded-2xl border border-transparent bg-input/50 px-2.5 text-sm"
                  value={draft.projectId}
                  onChange={(event) => {
                    const projectId = event.currentTarget.value
                    const project = projects.find((item) => item.id === projectId)
                    setDraft({
                      ...draft,
                      projectId,
                      connectorId: project?.connectorId ?? draft.connectorId,
                    })
                  }}
                >
                  <option value="">{t("selectProject")}</option>
                  {projects.map((project) => (
                    <option key={project.id} value={project.id}>
                      {project.name} · {connectorName(connectors, project.connectorId)}
                    </option>
                  ))}
                </select>
                <FieldError>{fieldError("target")}</FieldError>
              </Field>
              <Field data-invalid={Boolean(fieldError("target"))}>
                <FieldLabel htmlFor="schedule-connector">{t("connectorLabel")}</FieldLabel>
                <select
                  id="schedule-connector"
                  className="h-8 w-full rounded-2xl border border-transparent bg-input/50 px-2.5 text-sm"
                  value={draft.connectorId}
                  onChange={(event) => setDraft({ ...draft, connectorId: event.currentTarget.value })}
                >
                  <option value="">{t("selectConnector")}</option>
                  {connectors.map((connector) => (
                    <option key={connector.id} value={connector.id}>
                      {connector.name}
                    </option>
                  ))}
                </select>
                <FieldError>{fieldError("target")}</FieldError>
              </Field>
            </>
          )}

          {serverError ? <FieldError>{serverError}</FieldError> : null}
        </FieldGroup>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            {t("cancel")}
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={saving}>
            {saving ? t("saving") : t("create")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function connectorName(
  connectors: ReturnType<typeof useWorkspace>["connectors"],
  connectorId: string,
): string {
  return connectors.find((item) => item.id === connectorId)?.name ?? connectorId
}

/** Sessions come from the workspace snapshot, so no extra request is needed. */
function SessionPicker({
  id,
  value,
  onChange,
}: {
  id: string
  value: string
  onChange: (sessionId: string) => void
}) {
  const t = useTranslations("pages.schedules")
  const { sessions } = useWorkspace()
  const selectable = React.useMemo(
    () => sessions.filter((session) => !session.archived).slice(0, 100),
    [sessions],
  )
  return (
    <select
      id={id}
      className="h-8 w-full rounded-2xl border border-transparent bg-input/50 px-2.5 text-sm"
      value={value}
      onChange={(event) => onChange(event.currentTarget.value)}
    >
      <option value="">{t("selectSession")}</option>
      {selectable.map((session) => (
        <option key={session.id} value={session.id}>
          {session.title ?? t("untitled")}
        </option>
      ))}
    </select>
  )
}
