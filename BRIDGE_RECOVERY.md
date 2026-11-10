# 重启 DSH 后验证手机连接是否恢复

重启 DSH 后，**先完成一次接入**（否则 bridge 仍不会注册）：

1. 左侧边栏打开「远程控制」；
2. 服务端地址填：你的服务器地址（见本地私有副本 `~/.dsh/private-ops/BRIDGE_RECOVERY.md`，仓库里不保存）；
3. 在浏览器里完成登录/授权。

然后跑这个脚本验证（三条都是客观事实，不依赖界面）：

```bash
AA_VERIFY_SERVER=user@your-host AA_VERIFY_SSH_KEY=/abs/path/key bash verify-bridge.sh
```

它检查：

1. `~/.agent-pilot/dsh-bridge-next/connector/connector.json` 是否有连接身份（重启前是**空的**，这是"从未接入"的判据；旧插件路径 `~/.dsh/agents-anywhere/bridge` 已停用）；
2. server 上 dsh runtime 的状态是否从 `starting` 变成 `running`；
3. server 上其他 runtime 是否仍健康。

> 连接目标与 SSH key 一律从 `AA_VERIFY_SERVER` / `AA_VERIFY_SSH_KEY` 读，仓库里不含任何服务器地址。
