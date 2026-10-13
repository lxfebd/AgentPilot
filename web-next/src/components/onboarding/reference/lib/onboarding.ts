export const onboarding = {
  agents: ["Codex", "Claude Code", "DSH"],
  downloadsUrl: "https://github.com/lxfebd/AgentPilot/releases",
  // Open-source build: no operator website. Deployments may point this at their
  // own docs page; keep it empty to hide the link.
  learnMoreUrl: "",
} as const

export const steps = [
  { id: "welcome", label: "认识 Agent" },
  { id: "device", label: "配置设备" },
  { id: "phone", label: "连接手机" },
  { id: "complete", label: "准备就绪" },
] as const

export type DialogKind = "phone" | "skip"

export type SlideProps = {
  onNext: () => void
}
