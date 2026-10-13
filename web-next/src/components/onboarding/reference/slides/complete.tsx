import { ArrowRightIcon, ArrowUpRightIcon, CheckIcon, DownloadIcon, StarIcon } from "lucide-react"
import { SlideFrame } from "@/components/onboarding/reference/components/slide-frame"
import { Button } from "@/components/onboarding/reference/components/ui/button"
import { onboarding } from "@/components/onboarding/reference/lib/onboarding"

export function CompleteSlide({ onExperience, showDownload = true }: {
  onExperience: (trigger: HTMLButtonElement) => void
  /** Desktop hides it: the user is already running the desktop app. */
  showDownload?: boolean
}) {
  return (
    <div className="complete-slide-wrap">
      <SlideFrame
        id="complete"
        title={"You are\nall set."} englishTitle
        titleSuffix={<CheckIcon className="completion-check" strokeWidth={2.1} />}
        description={"点击「立刻体验」，\n开始使用 AgentPilot。"}
        actions={<>
          <Button size="lg" onClick={(event) => onExperience(event.currentTarget)}>立刻体验<ArrowRightIcon data-icon="inline-end" /></Button>
          {showDownload
            ? <Button variant="outline" size="lg" asChild><a href={onboarding.downloadsUrl} target="_blank" rel="noreferrer"><DownloadIcon data-icon="inline-start" />下载桌面程序</a></Button>
            : null}
          {onboarding.learnMoreUrl ? <Button variant="ghost" size="lg" asChild><a href={onboarding.learnMoreUrl} target="_blank" rel="noreferrer">了解更多<ArrowUpRightIcon data-icon="inline-end" /></a></Button> : null}
        </>}
      />
      <footer className="complete-slide-support" aria-label="支持这个项目">
        <a href="https://github.com/lxfebd/AgentPilot" target="_blank" rel="noreferrer">
          <StarIcon data-icon="inline-start" />支持这个项目 · 去 GitHub 点 Star
        </a>
        <span className="complete-slide-support-meta">
          基于 <a href="https://github.com/anywhere-labs/Agents-Anywhere" target="_blank" rel="noreferrer">Agents Anywhere</a> 二次开发
        </span>
      </footer>
    </div>
  )
}
