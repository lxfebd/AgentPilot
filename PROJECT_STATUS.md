# 项目进度

> **本文件是公开模板**：只记录与代码相关的进度与验证状态。
> 你的服务器地址、IP、SSH key 名、容器名、待办运维事项等**个人/生产信息不放在这里**，
> 已移到本地私有副本 `~/.dsh/private-ops/PROJECT_STATUS.md`（在仓库外，不会外泄）。
> 需要查生产环境信息时看那个文件。

## 一、当前版本（master）

见 `git log -1` 与各子项目的 `versionName` / `package.json`。版本号由代码与 CI 决定，不手写在此。

## 二、已交付并合入 master 的能力

- AgentPilot 跨设备工作台：账号 / 会话 / Timeline / 审批 / 文件 / 终端
- 各端客户端：Desktop（`desktop-workbench`）、Web（`web-next`）、Android（`android`）、iOS（`ios`）
- DSH 桥接插件（`dsh-bridge-next`）：连接并驱动 DSH runtime
- 手机端内嵌真 DSH Web 控制台：HTTPS 反向隧道 + 服务端免登录入口 + 旧 WebView 兼容

## 三、验证状态

| 范围 | 命令 |
| --- | --- |
| Server | `cd server && uv run ruff check . --exclude .venv && uv run pytest -q` |
| Connector | `cd connector && uv run ruff check connector tests && uv run pytest -q` |
| Web | `cd web-next && yarn test && yarn typecheck && yarn protocol:check` |
| Desktop | `cd desktop-workbench && yarn test:main && yarn renderer:typecheck` |
| Android | `cd android && ./gradlew :app:testDebugUnitTest` |
| iOS | 单测尚未加入 Xcode target，需在 Xcode 中运行 |

已通过 headless 检查，**不等于**真实安装、OAuth 回调或远程 Runtime 会话已验证——汇报时说明哪些没做。

## 四、单人使用时的日常流程（不需要发布链）

单人使用时不必走「Server 版本 + 安装包 + APK」发布链：

1. `cd android && ./gradlew :app:assembleDebug`
2. 把 `app/build/outputs/apk/debug/app-debug.apk` 装到手机
3. 服务器侧的 nginx/gzip/反向隧道改动直接改在生产机（改前备份，注明改了什么）

> 若将来要多用户分发，再按 AGENTS.md 的发布链补齐：Server 版本、release 签名 keystore、
> `/apk/latest.apk` 上传、实测 URL。

## 五、凭据与隐私约定（重要）

- **任何 token / 服务器地址 / SSH key 路径都不进仓库**：一律走 gitignored 的
  `local.properties`、命令行 `-P` 参数、或仓库外的私有目录
- nginx 里的免登录入口路径是**凭据**，别把配置文件原样提交
- 本仓库是公开仓库时尤其要遵守上面两条

## 六、维护这份文件

- 每批合并后更新「当前版本」与「已交付能力」两节
- 生产环境的真实信息写进本地私有副本，不要写回本文件
