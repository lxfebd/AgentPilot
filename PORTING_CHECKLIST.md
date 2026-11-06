# 跨仓库移植防再犯检查单

从 Agents Anywhere → AgentPilot 这批回搬中，审计抓出 **20 个"文件在、功能死"的缺口**。它们的共同点是：**typecheck 绿、相关测试过、CI 过，但功能不可用**。本清单按根因归类，每条给出当年实例与移植时的必做动作。

适用场景：从上游仓库移植功能到本仓库（尤其是协议/多端功能）。

---

## A. "搬了文件 ≠ 接了线"（10 个，最高发）

**根因**：上游把功能切成若干文件，移植时只拷了组件/模型文件，没拷"谁调用它"的那一段。

| 实例 | 上游有 | 移植时漏了 |
|---|---|---|
| web/desktop Plan UI 未显示 | `session-detail` 里 `useSessionPlanning` + `<PlanningPanel>` + `PlanningReader` | 三个文件拷了，detail 里零引用 |
| desktop planning 模块 | `features/planning/` 4 个文件 + composer 命令 + detail 挂载 | 一个文件都没拷 |
| android/iOS Plan 审阅 | `respondPlan` + `planReviewTarget` + 面板挂载 | 面板拷了、方法没拷 |
| web/desktop `/plan` 发送 | `handleSend` 的 planIntent 分支 | 分支整个没有 |
| web/desktop 实时刷新 | `planning.state.updated` → `planning.ingest` | dispatch 里没这个分支 |
| android Plan 发起 | `sendDraft` 的 `/plan` 解析 + `executePlanning` | 解析器拷了没调用 |

**必做动作**：
1. 对每个新拷的文件，`grep` 它的导出符号在**目标侧的调用方**（不是它自己）；
2. 拿上游同文件做**逐函数 diff**，特别看 `render/useEffect/handle*/send*` 里的分支；
3. 组件若接收 `onXxx` 回调，确认目标侧真的传了实现（不是 `{}` 或缺省参数）。
4. **问自己"用户点这一步会发生什么"**——答不上就等于没验。

## B. 协议/派发层的"方法名对齐"（1 个，最致命）

**实例**：connector 的 `RuntimeRpcHandler.METHODS` 没有 `WORKFLOW_RPC` 的 16 个方法名 → server 发的 RPC 全被拒 → Pi 会话在任何客户端都不可能创建成功。

**必做动作**：
1. 列出 server/客户端**发送的每个方法名/路径**，与接收方**声明的集合**做集合比对（`comm -23`）；
2. 命中"发送方有、接收方没有"的，逐个确认是"本就不支持"还是"漏注册"；
3. 有映射表（长名→短名）时，核对映射表的**双向覆盖**（键数 = 处理分支数）。

## C. i18n 资源与键结构（5 个）

**实例**：
- web 的 54 个 planning 键被**压平**成 `planning.title` 嵌套在 planning 对象里（`useTranslations('planning')`+`t('title')` 全落空）；
- desktop 整个 planning 命名空间不存在；
- 两端缺 `dashboard.session.nativeHistory` 15 键；
- iOS String Catalog 缺 162 条（英文界面显示中文）；
- 上游要求的死键（报错路径显示 raw key）。

**必做动作**：
1. **解析式校验，不要数数**：把组件里 `t("key")`/`String(localized:)`/`R.string.x` 的实参提取出来，去 messages/catalog 里逐个解析（`resolve` 到叶子）；
2. 注意 **键的形态**（嵌套 vs 扁平带前缀）要与 `useTranslations` 的命名空间匹配；
3. 多语言要**每个 locale 都验**，不能只验一种；
4. iOS 的 `.xcstrings` 是 JSON，**本地就能验**（不需要 Mac）；
5. 顺手查"上游独有资源文件"（如 AA 的 `planning_strings.xml`）是否整份漏拷。

## D. 契约/生成层（2 个）

**实例**：`export-acp-contracts.py` 模板写上游 `urn:agents-anywhere`（无人调用，但一跑就把 AA 身份写回契约）；脚本在 Windows 文本模式下写 CRLF，一跑就弄脏已提交契约。

**必做动作**：
1. 搜**生成器/脚本里的身份标识**（urn、包名、CLI 名、环境变量名），不只搜源码；
2. 对"会写回仓库"的脚本，跑一次（重定向 ROOT 到临时目录）验证产物与已提交版**逐字节一致**；
3. Windows 上写文本文件要显式 `newline="\n"`。

## E. 命名改名的"只改一半"（1 个，静默失效）

**实例**：`AA_PI_NPM_ROOT` → `AGENTPILOT_PI_NPM_ROOT` 只改了 desktop（Electron），connector（Python）仍读旧名 → desktop 传给 connector 的配置**被静默忽略**，Pi 适配器备到默认目录。

**必做动作**：
1. 改名时**全仓 grep 旧名**，逐个判定"保留做兼容"还是"漏改"；
2. **兼容方向要对**：新名优先、旧名兜底（`os.environ.get(新) or os.environ.get(旧)`）；
3. 检查有没有出现"同一个变量读两次"（`A ?? A`）——那是把兼容读覆盖掉的典型痕迹。

## F. 基类/协议缺属性（1 个）

**实例**：`AgentRuntime(ABC)` 没声明 `planning`，而派发代码读 `runtime.planning` → 真实 runtime 抛 `AttributeError`，且 server 的 `except` 抓不住它。

**必做动作**：
1. 移植"读 `X.foo`"的代码时，同步检查 `X` 的基类/协议在目标侧**是否声明了 `foo`**（含默认值）；
2. 上游用 `getattr(self, "_foo", None)` 这类默认实现时，**必须一起搬**；
3. **测试替身不要自带该属性**——那会掩盖真实对象的缺失（本批正是 stub 的 `planning = None` 隐藏了它）。

## G. 副作用时序（1 个）

**实例**：创建会话前 `clear()` 用默认值撤销了 blob 预览 URL，而乐观消息与时间线读 `attachment.previewUrl` → 附件预览破图。

**必做动作**：
1. 移植"移交所有权"类代码时，**逐个确认副作用开关**（默认参数值）与上游一致；
2. 查被移交对象的**消费方**是否依赖那些副作用产物（URL、ticket、handle）。

## H. 静态验证够不到的地方（本批 3 批 iOS 的教训）

**必做动作**：
1. 本机无工具链时，至少做：**括号/圆括号配平**（剥离字符串与注释后计数）、**零上游命名泄漏**、**接线符号在位**；
2. 但必须在交付物里**明确写出"运行期未验证"**，并给 Mac/设备侧一份可执行清单；
3. 能补的探针要补（本批为 iOS 补了 planning 线缆探针，覆盖自带探针没跑的路径）。

---

## 一句话总结

> **typecheck 绿 ≠ 功能通。** 每移植一个功能，必须回答三个问题：**① 用户从哪触发它？② 触发后数据流到哪个端口？③ 那个端口在目标侧存在吗？**
