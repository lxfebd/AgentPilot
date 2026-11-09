# 重启 DSH 后验证手机连接是否恢复

重启 DSH 后，**先完成一次接入**（否则 bridge 仍不会注册）：

1. 左侧边栏打开「远程控制」；
2. 服务端地址填：`https://smartcare.aifacex.top`；
3. 在浏览器里完成登录/授权。

然后跑这个脚本验证（三条都是客观事实，不依赖界面）：

```bash
bash verify-bridge.sh
```

它检查：

1. `~/.dsh/agents-anywhere/bridge/` 是否有凭证文件（重启前是**空的**，这是"从未接入"的判据）；
2. server 上 `rti_i9hdNpn` 的状态是否从 `starting` 变成 `running`；
3. server 上其他 runtime 是否仍健康。
