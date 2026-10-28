#!/usr/bin/env bash
# AgentPilot 本地冒烟检查（重构步 0）。
#
# 默认只读：契约一致性 + Server 健康 + Web 可达 + 会话列表可读。
# 写操作必须显式 --write / --register；Runtime 往返（消息 + 审批）本轮未覆盖。
#
# 用法：
#   ./devtools/smoke-local.sh                       # 对着本地已起的栈做只读检查
#   ./devtools/smoke-local.sh --email a@b.c --password ***   # 额外用现有账号登录并列会话
#   ./devtools/smoke-local.sh --register --email a@b.c --password ***
#   ./devtools/smoke-local.sh --session-id ses_xxx --write    # 归档 → 取消归档往返
#   ./devtools/smoke-local.sh --base-url https://host --skip-web --skip-contracts
#
# 退出码：0 全部通过或跳过；1 有 FAIL。
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${AGENT_PILOT_SMOKE_SERVER_URL:-http://127.0.0.1:8000}"
WEB_URL="${AGENT_PILOT_SMOKE_WEB_URL:-http://127.0.0.1:5174}"
TIMEOUT="${AGENT_PILOT_SMOKE_TIMEOUT:-5}"

DO_WRITE=false
DO_REGISTER=false
SKIP_WEB=false
SKIP_CONTRACTS=false
SKIP_SESSIONS=false
QUIET=false
EMAIL=""
PASSWORD=""
SETUP_TOKEN="${AGENT_PILOT_SETUP_TOKEN:-}"
SESSION_ID=""

PASS=0
FAIL=0
SKIP=0
FAILED=()

usage() {
  cat <<'EOF'
AgentPilot 本地冒烟检查（重构步 0）

用法：
  ./devtools/smoke-local.sh                                  只读检查
  ./devtools/smoke-local.sh --email a@b.c --password PW      额外登录并列会话
  ./devtools/smoke-local.sh --register --email a@b.c --password PW [--setup-token T]
  ./devtools/smoke-local.sh --write --session-id ses_x       归档 → 取消归档往返
  ./devtools/smoke-local.sh --base-url URL --skip-web --skip-contracts

选项：
  --base-url URL    Server 地址（默认 http://127.0.0.1:8000）
  --web-url URL     Web 地址（默认 http://127.0.0.1:5174）
  --timeout N       每个请求的超时秒数（默认 5）
  --email/--password  现有账号；与 --register 同用时先注册
  --setup-token T   首次注册（库中无用户）所需的 setup token
  --write           允许写操作（归档/取消归档）
  --register        先注册再登录
  --skip-web | --skip-contracts | --skip-sessions
  --quiet           只打印 FAIL 与汇总
  -h, --help        显示本帮助

退出码：0 全通过或有跳过；1 有 FAIL。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --web-url) WEB_URL="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --email) EMAIL="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --setup-token) SETUP_TOKEN="$2"; shift 2 ;;
    --session-id) SESSION_ID="$2"; shift 2 ;;
    --write) DO_WRITE=true; shift ;;
    --register) DO_REGISTER=true; shift ;;
    --skip-web) SKIP_WEB=true; shift ;;
    --skip-contracts) SKIP_CONTRACTS=true; shift ;;
    --skip-sessions) SKIP_SESSIONS=true; shift ;;
    --quiet) QUIET=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass() { PASS=$((PASS + 1)); [ "$QUIET" = true ] || printf 'PASS  %s\n' "$1"; }
fail() {
  FAIL=$((FAIL + 1)); FAILED+=("$1")
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '      %s\n' "$2"
  return 0
}
skip() {
  SKIP=$((SKIP + 1))
  printf 'SKIP  %s\n' "$1"
  [ -n "${2:-}" ] && printf '      %s\n' "$2"
  return 0
}

# 提取顶层字符串字段（不依赖 jq/python）
jstr() { tr -d '\n\r' < "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"; }

http() { # http METHOD URL [DATA] -> 打印状态码, body 落 $TMP/body
  local method="$1" url="$2" data="${3:-}" code
  if [ -n "$data" ]; then
    code=$(curl -sS -m "$TIMEOUT" -X "$method" -H 'content-type: application/json' \
      -o "$TMP/body" -w '%{http_code}' -d "$data" "$url" 2>"$TMP/curlerr" || true)
  else
    code=$(curl -sS -m "$TIMEOUT" -X "$method" -o "$TMP/body" -w '%{http_code}' "$url" 2>"$TMP/curlerr" || true)
  fi
  printf '%s' "${code:-000}"
}

# --- 1. 契约一致性 ---------------------------------------------------------
run_contract_check() {
  local app="$1"
  if [ ! -d "$ROOT_DIR/$app/node_modules" ]; then
    skip "$app protocol:check" "未安装依赖（cd $app && yarn install）"
    return 0
  fi
  if (cd "$ROOT_DIR/$app" && yarn -s protocol:check >"$TMP/pc.log" 2>&1); then
    pass "$app protocol:check"
  else
    fail "$app protocol:check" "$(tail -n 3 "$TMP/pc.log" | tr '\n' ' ' | cut -c1-300)"
  fi
}

[ "$SKIP_CONTRACTS" = true ] && skip "contracts protocol:check" "--skip-contracts" || {
  run_contract_check web-next
  run_contract_check desktop-workbench/renderer
}

# --- 2. Server 健康 -------------------------------------------------------
SERVER_UP=false
code=$(http GET "$BASE_URL/api/v2/health/live")
if [ "$code" = "200" ] && [ "$(jstr "$TMP/body" status)" = "ok" ]; then
  pass "server health/live"
  SERVER_UP=true
else
  fail "server health/live" "HTTP $code @ $BASE_URL（栈没起？端口被别的服务占用？先 ./local-up.sh）"
fi

if [ "$SERVER_UP" = true ]; then
  code=$(http GET "$BASE_URL/api/v2/health/ready")
  if [ "$code" = "200" ]; then
    pass "server health/ready"
  else
    fail "server health/ready" "HTTP $code：$(cut -c1-300 "$TMP/body")"
  fi
fi

# --- 3. Web 可达 ----------------------------------------------------------
if [ "$SKIP_WEB" = true ]; then
  skip "web 首页" "--skip-web"
elif [ "$SERVER_UP" = false ]; then
  skip "web 首页" "server 未就绪，跳过"
else
  code=$(http GET "$WEB_URL/")
  [ "$code" = "200" ] && pass "web 首页 $WEB_URL" || fail "web 首页 $WEB_URL" "HTTP $code"
fi

# --- 4. 账号（可选） -----------------------------------------------------
TOKEN=""
if [ -n "$EMAIL" ] && [ -n "$PASSWORD" ]; then
  register_path="/api/v2/auth/register"
  if [ "$DO_REGISTER" = true ]; then
    body="{\"email\":\"$EMAIL\",\"displayName\":\"smoke\",\"password\":\"$PASSWORD\""
    [ -n "$SETUP_TOKEN" ] && body="$body,\"setupToken\":\"$SETUP_TOKEN\""
    body="$body}"
    code=$(http POST "$BASE_URL$register_path" "$body")
    if [ "$code" = "200" ] || [ "$code" = "201" ]; then
      TOKEN=$(jstr "$TMP/body" accessToken)
      pass "注册 $EMAIL"
    else
      fail "注册 $EMAIL" "HTTP $code：$(cut -c1-200 "$TMP/body")"
    fi
  fi
  if [ -z "$TOKEN" ]; then
    code=$(http POST "$BASE_URL/api/v2/auth/login" "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
    if [ "$code" = "200" ]; then
      TOKEN=$(jstr "$TMP/body" accessToken)
      [ -n "$TOKEN" ] && pass "登录 $EMAIL" || fail "登录 $EMAIL" "200 但没有 accessToken"
    else
      fail "登录 $EMAIL" "HTTP $code：$(cut -c1-200 "$TMP/body")"
    fi
  fi
else
  skip "账号登录" "未提供 --email/--password（--register 可顺带注册）"
fi

# --- 5. 会话列表 / 归档往返（可选） ---------------------------------------
if [ "$TOKEN" = "" ]; then
  skip "sessions/list" "没有 token"
  skip "session archive 往返" "没有 token"
elif [ "$SKIP_SESSIONS" = true ]; then
  skip "sessions/list" "--skip-sessions"
  skip "session archive 往返" "--skip-sessions"
else
  code=$(curl -sS -m "$TIMEOUT" -H "authorization: Bearer $TOKEN" \
    -o "$TMP/list.json" -w '%{http_code}' "$BASE_URL/api/v2/sessions/list" 2>/dev/null || true)
  if [ "${code:-000}" = "200" ] && grep -q '"sessions"' "$TMP/list.json"; then
    count=$(grep -o '"sessionId"' "$TMP/list.json" | wc -l | tr -d ' ')
    pass "sessions/list（$count 条）"
  else
    fail "sessions/list" "HTTP ${code:-000}：$(cut -c1-200 "$TMP/list.json" 2>/dev/null)"
  fi

  if [ "$DO_WRITE" = true ] && [ -n "$SESSION_ID" ]; then
    code=$(curl -sS -m "$TIMEOUT" -X POST -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
      -d "{\"sessionIds\":[\"$SESSION_ID\"],\"archived\":true}" -o "$TMP/a.json" -w '%{http_code}' \
      "$BASE_URL/api/v2/sessions/archive" 2>/dev/null || true)
    if [ "${code:-000}" = "200" ]; then
      pass "archive $SESSION_ID"
      code=$(curl -sS -m "$TIMEOUT" -X POST -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
        -d "{\"sessionIds\":[\"$SESSION_ID\"],\"archived\":false}" -o "$TMP/b.json" -w '%{http_code}' \
        "$BASE_URL/api/v2/sessions/archive" 2>/dev/null || true)
      [ "${code:-000}" = "200" ] && pass "unarchive $SESSION_ID" || fail "unarchive $SESSION_ID" "HTTP ${code:-000}"
    else
      fail "archive $SESSION_ID" "HTTP ${code:-000}：$(cut -c1-200 "$TMP/a.json" 2>/dev/null)"
    fi
  else
    skip "session archive 往返" "需要 --write --session-id"
  fi
fi

# --- 6. Runtime 往返（未覆盖） --------------------------------------------
skip "Runtime 消息 + 审批往返" "本轮未覆盖：需要在线 Connector 与 Runtime 凭据；接口点为 POST /api/v2/sessions、/api/v2/sessions/{id}/timeline、审批裁决端点"

# --- 汇总 -----------------------------------------------------------------
printf '\n%d passed, %d skipped, %d failed\n' "$PASS" "$SKIP" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'failed checks:\n'
  for n in "${FAILED[@]}"; do printf '  - %s\n' "$n"; done
  exit 1
fi
exit 0
