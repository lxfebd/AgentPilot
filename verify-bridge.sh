#!/usr/bin/env bash
# 验证 DSH bridge 是否已恢复（重启 DSH 并完成接入后运行）
# 三条检查都是客观事实，不依赖任何界面：
#   1) 本机 bridge 凭证目录是否已写入（重启前为空 = 从未接入）
#   2) server 上 dsh runtime 是否已从 starting 转为 running
#   3) 其他 runtime 是否仍然健康
#
# 连接目标与 SSH key 一律从环境变量读，**仓库里不保存任何服务器地址或凭据路径**：
#   AA_VERIFY_SERVER=user@host  AA_VERIFY_SSH_KEY=/abs/path/key  ./verify-bridge.sh
set -u

SERVER_ROOT="${AA_VERIFY_SERVER:-}"
SSH_KEY="${AA_VERIFY_SSH_KEY:-$HOME/.ssh/id_rsa}"

if [ -z "$SERVER_ROOT" ]; then
  echo "未设置 AA_VERIFY_SERVER（例如 user@your-host），退出。" >&2
  echo "用法: AA_VERIFY_SERVER=user@host AA_VERIFY_SSH_KEY=/abs/key ./verify-bridge.sh" >&2
  exit 2
fi

echo "=== 1. 本机 bridge 凭证 ==="
CONNECTOR_JSON="$HOME/.agent-pilot/dsh-bridge-next/connector/connector.json"
BRIDGE_DIR="$HOME/.agent-pilot/dsh-bridge"
STATE_DIR="$HOME/.agent-pilot/dsh-bridge-next"
LEGACY_DIR="$HOME/.dsh/agents-anywhere/bridge"

if [ -f "$CONNECTOR_JSON" ]; then
  echo "   ✅ 已写入连接凭证: $CONNECTOR_JSON"
  grep -o '"serverUrl"[^,]*' "$CONNECTOR_JSON" | sed 's/^/      /'
  grep -o '"connectorId"[^,]*' "$CONNECTOR_JSON" | sed 's/^/      /'
else
  echo "   ❌ 未找到连接凭证: $CONNECTOR_JSON"
  echo "      （DSH 插件版本较新时也可能写在"
  echo "       \$HOME/AppData/Roaming/AgentPilot/connector/connector.json）"
  APPDATA_JSON="$HOME/AppData/Roaming/AgentPilot/connector/connector.json"
  [ -f "$APPDATA_JSON" ] && echo "      · AppData 副本存在: $APPDATA_JSON"
fi
for d in "$BRIDGE_DIR" "$STATE_DIR"; do
  if [ -d "$d" ]; then
    echo "   · $d : $(find "$d" -type f 2>/dev/null | wc -l) 个文件"
  else
    echo "   · $d : 不存在"
  fi
done
if [ -d "$LEGACY_DIR" ]; then
  echo "   · $LEGACY_DIR（旧路径，已停用，非判断依据）: $(find "$LEGACY_DIR" -type f 2>/dev/null | wc -l) 个文件"
fi

echo
echo "=== 2. server 上 dsh runtime 状态 ==="
if [ ! -f "$SSH_KEY" ]; then
  echo "   ⚠️  找不到 SSH key: $SSH_KEY（用 AA_VERIFY_SSH_KEY 指定）"
else
  ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$SERVER_ROOT" \
    "docker exec agents-anywhere-postgres-next-1 psql -U agents_anywhere -d agents_anywhere -Atc \
     \"SELECT runtime_id||' | '||runtime_type||' | '||status||' | active='||active||' | '||COALESCE((error_json::json->>'code'),'-') FROM device_runtimes ORDER BY updated_at DESC;\"" \
    2>/dev/null | while IFS= read -r row; do
      case "$row" in
        *"| dsh | running"*) echo "   ✅ $row" ;;
        *"| dsh | starting"*) echo "   ⏳ $row  （仍在等 bridge）" ;;
        *) echo "   · $row" ;;
      esac
    done
fi

echo
echo "=== 3. 结论 ==="
echo "   若第 1 条找到 connector.json 且第 2 条 dsh 为 running，"
echo "   手机端会话消息即恢复更新。"
echo "   注意：connector 进程若被外部杀掉，DSH 插件不会自动重拉它，"
echo "   需要从插件日志页重试一次（凭证仍在，无需重新登录）。"
