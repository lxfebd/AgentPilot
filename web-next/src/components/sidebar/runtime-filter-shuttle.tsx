"use client"

import * as React from "react"
import { SessionAgentIcon } from "@/components/sidebar/session-agent-icon"
import {
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
} from "@/components/ui/sidebar"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { runtimeLabel } from "@/components/session/session-utils"
import { useWorkspace } from "@/components/workspace-context"
import { cn } from "@/lib/utils"
import { useTranslations } from "next-intl"

/**
 * Agent switcher at the top of the sidebar: one icon per runtime that has
 * sessions (zcode / codex / claude / dsh / …), plus an "All" tab. Clicking an
 * icon narrows every session list below it to that agent's conversations via
 * the shared `filter.runtime` mechanism — the same filter the session-filter
 * menu sets, so the two stay in sync.
 */
export function RuntimeFilterShuttle() {
  const { sessions, filter, setFilter } = useWorkspace()
  const t = useTranslations("dashboard")

  const runtimes = React.useMemo(
    () => Array.from(new Set(sessions.map((s) => s.runtime))).sort(),
    [sessions],
  )

  if (runtimes.length === 0) return null

  const select = (runtime: string) => setFilter({ ...filter, runtime })

  return (
    <SidebarGroup>
      <SidebarGroupLabel className="text-xs text-muted-foreground">
        {t("filters.agents")}
      </SidebarGroupLabel>
      <SidebarGroupContent>
        <TooltipProvider delayDuration={200}>
          <div className="flex flex-wrap items-center gap-1 px-2">
            <ShuttleTab
              active={filter.runtime === "all"}
              label={t("filters.allAgents")}
              onClick={() => select("all")}
            >
              {t("filters.all")}
            </ShuttleTab>
            {runtimes.map((runtime) => (
              <Tooltip key={runtime}>
                <TooltipTrigger asChild>
                  <ShuttleTab
                    active={filter.runtime === runtime}
                    label={runtimeLabel(runtime)}
                    onClick={() => select(runtime)}
                  >
                    <SessionAgentIcon runtime={runtime} className="size-4" />
                  </ShuttleTab>
                </TooltipTrigger>
                <TooltipContent side="right">
                  {runtimeLabel(runtime)}
                </TooltipContent>
              </Tooltip>
            ))}
          </div>
        </TooltipProvider>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}

function ShuttleTab({
  active,
  label,
  onClick,
  children,
}: {
  active: boolean
  label: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        "inline-flex h-7 items-center justify-center gap-1.5 rounded-md px-1.5 text-xs font-medium transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring",
        active
          ? "bg-sidebar-accent text-sidebar-accent-foreground"
          : "text-sidebar-foreground/60 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground",
      )}
    >
      {children}
    </button>
  )
}