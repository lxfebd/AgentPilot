"use client"

import * as React from "react"
import { API_NAMESPACE } from "@/lib/api/client"
import { safeGet, safeSet } from "@/lib/safe-storage"
import { createProjectSidebarPreferenceStore, projectSidebarPreferenceKey } from "./project-sidebar-preferences"

export function useProjectSidebarPreferences(userId: string) {
  const server = JSON.stringify([process.env.NEXT_PUBLIC_AGENT_PILOT_API ?? "", API_NAMESPACE])
  const key = projectSidebarPreferenceKey(server, userId)
  const store = React.useMemo(() => {
    const storage: Pick<Storage, "getItem" | "setItem"> = {
      getItem: safeGet,
      setItem: safeSet,
    }
    return createProjectSidebarPreferenceStore(storage, key)
  }, [key])
  const preferences = React.useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)

  React.useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === key || event.key === null) store.refresh()
    }
    window.addEventListener("storage", onStorage)
    return () => window.removeEventListener("storage", onStorage)
  }, [key, store])

  return { preferences, setProjectExpanded: store.setProjectExpanded, setProjectsExpanded: store.setProjectsExpanded }
}
