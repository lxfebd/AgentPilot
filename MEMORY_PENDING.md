# 待沉淀到记忆库的知识（memory-eternal API 恢复后可写入）

> 本文件是**暂存**，不是知识卡本体。写入通道未运行（memory-eternal HTTP API 未启动），
> 而规则禁止用文件工具直接改 vault。等 API 可用后，按下面的条目写入，然后删除本文件。

## 条目

### 1. 部署拓扑（线上站点）
- **服务器**：`smartcare.aifacex.top` → 公网 `124.221.30.114`（腾讯云），root + `~/.ssh/xuetu_deploy` 可登。
- **宿主 nginx**（非容器）提供静态路由：`/apk/`→`/opt/aa/apk/`、`/desktop-installer/`→`/opt/aa/desktop/`。
- **容器**（`docker run` 手工起，compose 文件不在这台机器上）：
  - `agents-anywhere-server-next-1`（镜像 `agents-anywhere-server:postgres`，`:5174`）
  - `agents-anywhere-redis-next-1`、`agents-anywhere-postgres-next-1`
  - volume 挂 `/data`（用户文件）
- **数据库**：`psql -U agents_anywhere -d agents_anywhere`（口令在容器 env `AGENT_SERVER_DB_URL`）。runtime 表是 **`device_runtimes`**，列名 `runtime_id/runtime_type/status/active/error_json`（**不是** `id/kind`）。
- **构建上下文不在服务器**（`/root/xuetu` 是另一个 Node 项目；`/opt/aa/Agents-Anywhere-main` 是旧源码，与容器内代码不一致）。

### 2. 发布链规则（最重要）
客户端判"有无新版"看 **Server `/api/v2/health` 的版本号**（与打包记录的 `serverVersion` 比）；产物地址是 `<origin>/desktop-installer/latest.exe` 与 `<origin>/apk/latest.apk`；安卓端另读 `/apk/version.json`。
→ **只换安装包不动 server = 无效发布**。发布后必须实测 URL（Content-Length / Last-Modified / version.json）。
现网 APK 是 **Android debug 证书**签的（`CN=Android Debug`，摘要 `2d053584…`）——换正式 keystore 时所有用户必须卸载重装。

### 3. DSH bridge 的 id 陷阱（30 天静默故障的根因）
- 插件 bundle 自报 id 是 **`agent-pilot-bridge-next`**（`insert` 形式，见 `dsh-bridge-next/cordis.patch.yml`）。
- 手写 profile patch 时若写成 `agents-anywhere-bridge-next`，**loader 匹配不到、不报错、静默无效**。
- `~/.dsh/profiles/desktop/node_modules/@agent-pilot/dsh-bridge-next` 是**软链到仓库源码**（改仓库即生效；仓库搬家则手机连接失效）。
- `~/.dsh/agents-anywhere/bridge/` **为空 = 从未完成接入**（重启 DSH 后还需手动走一次「远程控制」登录）。

### 4. 环境陷阱
- **`ELECTRON_RUN_AS_NODE=1`** 会让所有 Electron 应用秒退（退出码 0、无日志）。跑 electron 前先 `env -u`。
- **测试跑的是 `lib/` 而非 `src/`**（dsh-bridge-next）：改完 `src` 必须 `yarn build`，否则测的是旧构建。
- MuMu 模拟器：`adb connect 127.0.0.1:16384` / `7555`（多实例）；**`adb shell svc wifi/data disable` 会把 adb 通道一起断掉**，导致实例 offline。
- Windows/bash：`/sdcard` 等路径会被 MSYS 转换，需 `MSYS_NO_PATHCONV=1`；`taskkill //IM x //F`；bash 吃反引号（PR body 用 `--body-file`）。
- push 用 **HTTPS**（`git push https://github.com/lxfebd/AgentPilot.git`）——SSH 会拒连。

### 5. 本会话的方法论教训
- **截图会骗人**：我据截图误判过"巨型空卡"（实为 76dp 合理设计）、"无浅色模式"（实有双主题）、"字标仍是手写"（实为系统字体+负字距）——**每次都用代码/APK 内容取证后推翻**。
- **量化再动手**："128 处硬编码颜色该全迁"实测仅 27/215 是令牌值 → 主动停手，没造无用 diff。
- **CI 不要想当然**：我曾把 `DSH plugin` 失败归为"runner flaky"，实为真 bug（`native.ts` 非空断言）；一个只改 README 的 PR 触发它，才逼我查到底。
- **改完必看真机**；合并前必等 CI 绿（我曾破例，事后补验）。

### 6. 本会话交付（PR）
#105–#109、#110 移动端设计优化六项；#111 只读 composer 首点吞反馈；#112 刷新失败时错误被吞；#113 bridge id 文档；#114 planning 能力非空断言；#115 bridge 恢复手册 + `verify-bridge.sh`。
DSH 插件测试 **181/203 → 198/203**（剩余 5 失败均为本沙箱 `listen EACCES`）。
