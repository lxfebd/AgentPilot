import type { Metadata } from "next"

import { PrivacyPolicyScreen } from "@/components/pages/privacy-policy-screen"

export const metadata: Metadata = {
  title: "Privacy policy · AgentPilot",
  description:
    "How the AgentPilot web and desktop clients handle information.",
}

export default function PrivacyPage() {
  return <PrivacyPolicyScreen />
}
