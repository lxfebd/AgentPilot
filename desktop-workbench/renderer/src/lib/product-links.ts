/** Fixed product configuration. Empty URLs remain visibly unavailable. */
export const PRODUCT_LINKS: {
  desktopDownloadUrl: string
  landingPageUrl: string
  downloadPageUrl: string
  androidDownloadUrl: string
  iosDownloadUrl: string
  webAppHref: string
} = {
  desktopDownloadUrl: '',
  landingPageUrl: '',
  // Deployment-specific; open-source builds leave these empty so the UI shows
  // them as unavailable instead of pointing at a specific operator's site.
  downloadPageUrl: '',
  androidDownloadUrl: 'https://github.com/lxfebd/AgentPilot/releases/latest',
  iosDownloadUrl: '',
  webAppHref: '#/',
}
