#!/usr/bin/env bash
# 验证 DSH bridge 是否已恢复（重启 DSH 并完成接入后运行）
# 三条检查都是客观事实，不依赖任何界面：
#   1) 本机 bridge 凭证目录是否已写入（重启前为空 = 从未接入）
#   2) server 上 dsh runtime 是否已从 starting 转为 running
#   3) 其他 runtime 是否仍然健康
set -u

SERVER_ROOT=root@124.221.30.114
SSH_KEY=~/.ssh/xuetu_deploy
BRIDGE_DIR="$HOME/.dsh/agents-anywhere/bridge"

echo "=== 1. 本机 bridge 凭证 ==="
if [ -d "$BRIDGE_DIR" ]; then
  count=$(find "$BRIDGE_DIR" -type f 2>/dev/null | wc -l)
  echo "   $BRIDGE_DIR : $count 个文件"
  if [ "$count" -eq 0 ]; then
    echo "   ❌ 仍为空 —— bridge 未接入。请在 DSH 侧边栏打开「远程控制」，"
    echo "      填 https://smartcare.aifacex.top 并完成浏览器登录。"
  else
    echo "   ✅ 已写入凭证"
    find "$BRIDGE_DIR" -type f 2>/dev/null | head -3 | sed 's/^/      /'
  fi
else
  echo "   ❌ 目录不存在: $BRIDGE_DIR"
fi

echo
echo "=== 2. server 上 dsh runtime 状态 ==="
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

echo
echo "=== 3. 结论 ==="
echo "   若第 1 条为 ✅ 且第 2 条 dsh 为 running，手机端会话消息即恢复更新。"
