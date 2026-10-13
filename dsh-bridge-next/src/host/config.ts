import { userInfo } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import z from '@deepseek-ai/schemastery'
import type { ConnectionSettings } from '../contracts/index.js'
import { normalizeServerOrigin } from './account/server.js'

export interface Config {
  dshHome?: string
  apiBaseUrl?: string
  stateRoot?: string
  connectorSourceDir?: string
  uvPath?: string
}

export const Config: z<Config> = z.object({
  dshHome: z.string(),
  // No bundled cloud in the open-source build; blank means "self-hosted only".
  // Deployments can pin a default via plugin config to re-enable the cloud button.
  apiBaseUrl: z.string().default(''),
  stateRoot: z.string(),
  connectorSourceDir: z.string(),
  uvPath: z.string(),
})

export interface ResolvedConfig extends ConnectionSettings {
  dshHome?: string
  stateRoot: string
  legacyStateRoot?: string
  connectorSourceDir: string
  uvPath: string
}

export function stateRoot(config: Config): string {
  return config.stateRoot ?? join(userInfo().homedir, '.agent-pilot', 'dsh-bridge-next')
}

/** Fixed per-user rendezvous with the Connector; DSH_HOME and stateRoot do not move it. */
export function bridgeDirectory(): string {
  return join(userInfo().homedir, '.agent-pilot', 'dsh-bridge')
}

export function resolveConfig(config: Config): ResolvedConfig {
  if (config.dshHome !== undefined && !isAbsolute(config.dshHome)) throw new Error('DSH_HOME 必须是绝对路径。')
  const root = stateRoot(config)
  const connectorSourceDir = config.connectorSourceDir ?? fileURLToPath(new URL('./bundled-connector/', import.meta.url))
  if (!isAbsolute(root) || !isAbsolute(connectorSourceDir)) throw new Error('数据目录和 Connector 源码目录必须是绝对路径。')
  return {
    ...(config.dshHome !== undefined ? { dshHome: config.dshHome } : {}),
    stateRoot: root,
    ...(config.stateRoot === undefined ? { legacyStateRoot: join(userInfo().homedir, '.agentpilot', 'dsh-bridge-next') } : {}),
    connectorSourceDir,
    apiBaseUrl: config.apiBaseUrl?.trim() ? normalizeServerOrigin(config.apiBaseUrl) : '',
    uvPath: config.uvPath ?? process.env['UV_PATH'] ?? 'uv',
  }
}
