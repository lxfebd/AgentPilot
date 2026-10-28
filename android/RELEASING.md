# AgentPilot Android 发布流程

本文件描述把一个已合入 master 的版本（当前 2.3.5 / versionCode 26）发布到更新服务器需要的步骤。源码合入不等于发布成功；只有第 4 步之后，设备上的应用才会收到更新。

## 1. 准备签名密钥（每台发布机一次性）

发布 keystore 不进仓库（android/.gitignore 已排除 *.jks / *.keystore）。把属性写进 ~/.gradle/gradle.properties 或在命令行传 -P...：

    agentPilot.releaseStoreFile=/absolute/path/to/agentpilot-release.jks
    agentPilot.releaseStorePassword=<keystore password>
    agentPilot.releaseKeyAlias=<key alias>
    agentPilot.releaseKeyPassword=<key password>

密钥本身只存在于发布管理员有权限的地方。泄露等于要更换信任锚，因为同一个密钥也是更新链路（见第 4 步）的比对基准。

## 2. 构建发布包

    cd android
    ./release-build.sh

脚本输出 APK 路径、字节大小、SHA-256，并用 apksigner 打印签名证书摘要。如果密钥属性缺失，脚本以 NOT SIGNED 失败退出——不要把这种构建发布出去。

## 3. 签名核对（防误发错钥包）

比对两个证书指纹，必须一致：

- release-build.sh 打印的 SHA-256 证书摘要；
- 设备当前已安装版本的同证书指纹（apksigner verify --print-certs <设备上的旧 APK>，或 keytool -printcert -jarfile <旧 APK>）。

不一致（错密钥）发布后，设备端 ApkSignatureVerifier 会拒绝安装——这是预期保护，不是 bug。

## 4. 发布到更新服务器

1. 上传 APK 到服务端更新目录（AppConfig.updateDownloadUrl 指向的地址，现网为 smartcare 服务）。
2. 服务端更新清单追加一条：versionCode 26 / versionName 2.3.5 / downloadUrl / sha256。
3. 验证：设备进入设置 > 检查更新应看到 2.3.5；下载后签名校验通过才会进入安装。

上传与清单写入是生产操作，由发布管理员执行；本仓库不包含这些凭据。

## 5. 发布后

- 给 master 打 tag（如 android-v2.3.5）并 git push --tags。
- 在 GitHub Releases 附上 APK 与 sha256，便于追溯。
- 若该版本有跨端变更，按 AGENTS.md 同步所有端版本号并更新 README。

## 回滚

清单里把指向 2.3.5 的条目改回上一个 versionCode 即可；已下载未安装的包不受影响。设备若已装 2.3.5 出现严重问题，只能发更高 versionCode 的修复版（Android 不允许降级安装）。
