#!/bin/bash
# MindGraph 원클릭 실행 스크립트

set -e

MINDGRAPH_DIR="/home/kdw03/projects/mindgraph-ai"
LLMOPT_DIR="/home/kdw03/projects/llm-opt"
SESSION="mindgraph"

# ── 색상 ──────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[✓]${NC} $1"; }
info() { echo -e "${BLUE}[→]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
fail() { echo -e "${RED}[✗]${NC} $1"; exit 1; }

# ── Ctrl+C 트랩 ───────────────────────────────────────
trap 'echo ""; warn "중단됨 — tmux 세션 정리 중..."; tmux kill-session -t "$SESSION" 2>/dev/null; exit 1' INT TERM

# ── .env 로드 ─────────────────────────────────────────
ENV_FILE="$MINDGRAPH_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  set -o allexport; source "$ENV_FILE"; set +o allexport
else
  warn ".env 없음 — 기본값 사용"
fi

CHAT_MODEL="${CHAT_MODEL:-qwen2.5:14b}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-bge-m3}"
OLLAMA_HOST="${OLLAMA_HOST:-localhost}"
OLLAMA_PORT="${OLLAMA_PORT:-11434}"

# ── 사전 필수 도구 체크 ───────────────────────────────
check_deps() {
  info "필수 도구 확인 중..."
  local missing=()
  command -v docker  &>/dev/null || missing+=("docker")
  command -v tmux    &>/dev/null || missing+=("tmux")
  command -v node    &>/dev/null || missing+=("node")
  command -v java    &>/dev/null || missing+=("java")
  command -v curl    &>/dev/null || missing+=("curl")
  command -v lsof    &>/dev/null || missing+=("lsof")
  [ ${#missing[@]} -gt 0 ] && fail "필수 도구 없음: ${missing[*]}"
  log "필수 도구 확인 완료"
}

# ── 포트 정리: 헬스체크 먼저, 정상이면 skip ─────────
# usage: ensure_port_free <port> <health_cmd>
# health_cmd가 성공하면 이미 정상 실행 중 → skip
# health_cmd가 실패하면 포트 점유 프로세스 kill 후 포트 해제 대기
ensure_port_free() {
  local port="$1" health_cmd="$2"

  # 헬스체크 통과 → 이미 정상 실행 중, 재시작 불필요
  if [ -n "$health_cmd" ] && eval "$health_cmd" &>/dev/null; then
    warn "포트 $port — 이미 정상 실행 중, 재시작 skip"
    return 1  # skip 신호
  fi

  # 포트 점유 프로세스 kill
  set +e
  local pid
  pid=$(lsof -ti:"$port" 2>/dev/null)
  if [ -n "$pid" ]; then
    kill -9 "$pid" 2>/dev/null
    warn "포트 $port 프로세스($pid) 종료"
    # 포트가 실제로 해제될 때까지 대기 (최대 10초)
    local i=0
    while lsof -ti:"$port" &>/dev/null; do
      sleep 1; i=$((i+1))
      [ $i -ge 10 ] && { set -e; fail "포트 $port 해제 실패 (10s 초과)"; }
    done
  fi
  set -e
  return 0  # 시작 필요 신호
}

# ── 헬스체크 대기 ─────────────────────────────────────
wait_for() {
  local name="$1" cmd="$2" timeout="${3:-60}" i=0 max
  max=$((timeout / 2))
  info "$name 준비 대기 중..."
  until eval "$cmd" &>/dev/null; do
    sleep 2; i=$((i+1))
    echo -ne "\r  [${i}/${max}] 대기 중..."
    [ $((i * 2)) -ge $timeout ] && { echo ""; fail "$name 시작 실패 (${timeout}s 초과)"; }
  done
  echo -e "\r                          \r"
  log "$name 준비 완료"
}

# ═══════════════════════════════════════════════════════
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "   🧠  MindGraph 시작"
echo "   Chat: $CHAT_MODEL  |  Embed: $EMBEDDING_MODEL"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ── 1. 사전 체크 ──────────────────────────────────────
check_deps

# ── 2. 기존 tmux 세션 정리 ───────────────────────────
if tmux has-session -t "$SESSION" 2>/dev/null; then
  warn "기존 MindGraph tmux 세션 종료 중..."
  tmux kill-session -t "$SESSION"
  sleep 1
fi

# ── 3. 포트 정리 (이미 정상이면 skip 플래그 설정) ────
SKIP_LLMOPT=false
SKIP_SPRING=false
SKIP_VITE=false

ensure_port_free 8000 "curl -s http://localhost:8000/health | grep -q ok"  || SKIP_LLMOPT=true
ensure_port_free 8080 "curl -s http://localhost:8080/api/graph/nodes | grep -q nodes" || SKIP_SPRING=true
ensure_port_free 5173 "curl -s http://localhost:5173 | grep -q html" || SKIP_VITE=true

# ── 4. Docker 인프라 시작 ─────────────────────────────
info "Docker 인프라 시작..."
cd "$MINDGRAPH_DIR" && docker compose up -d --quiet-pull 2>/dev/null || docker compose up -d
cd "$LLMOPT_DIR"    && docker compose up redis -d --quiet-pull 2>/dev/null || docker compose up redis -d

# ── 5. 인프라 헬스체크 ───────────────────────────────
wait_for "PostgreSQL" "docker exec mindgraph-db pg_isready -U mindgraph_user -d mindgraph_db" 60
wait_for "RabbitMQ"   "curl -s http://localhost:15672 | grep -q RabbitMQ" 60
wait_for "Ollama"     "curl -s http://${OLLAMA_HOST}:${OLLAMA_PORT}/api/tags | grep -q models" 60
wait_for "Redis"      "docker exec llm-opt-redis redis-cli ping | grep -q PONG" 30

# ── 6. GPU 워밍업 ─────────────────────────────────────
echo ""
info "GPU 워밍업 — 모델을 VRAM에 로드합니다..."
echo -n "  → $CHAT_MODEL 로드 중..."
curl -s -X POST "http://${OLLAMA_HOST}:${OLLAMA_PORT}/api/generate" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${CHAT_MODEL}\",\"prompt\":\"hi\",\"stream\":false}" > /dev/null 2>&1 \
  && echo " 완료" || echo " 실패 (모델 미pull)"

echo -n "  → $EMBEDDING_MODEL 로드 중..."
curl -s -X POST "http://${OLLAMA_HOST}:${OLLAMA_PORT}/api/embed" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${EMBEDDING_MODEL}\",\"input\":\"warmup\"}" > /dev/null 2>&1 \
  && echo " 완료" || echo " 실패 (모델 미pull)"
log "GPU 워밍업 완료"

# ── 7. tmux 4분할 세션 생성 ──────────────────────────
info "tmux 4분할 세션 생성 중..."
tmux new-session -d -s "$SESSION" -x 220 -y 50
tmux rename-window -t "$SESSION:0" "MindGraph"
tmux split-window -h -t "$SESSION:0"
tmux split-window -v -t "$SESSION:0.0"
tmux split-window -v -t "$SESSION:0.2"

# 패널 0 (좌상): LLM-OPT
if $SKIP_LLMOPT; then
  tmux send-keys -t "$SESSION:0.0" "echo '── LLM-OPT (:8000) — 이미 실행 중 ──'; bash" Enter
else
  tmux send-keys -t "$SESSION:0.0" "echo '── LLM-OPT Proxy (:8000) ──'; cd $LLMOPT_DIR && LLM_MODE=ollama LLM_BACKEND=ollama EMBEDDING_MODEL=paraphrase-multilingual-MiniLM-L12-v2 uvicorn src.proxy.main:app --port 8000 2>&1" Enter
  wait_for "LLM-OPT" "curl -s http://localhost:8000/health | grep -q ok" 60
fi

# 패널 2 (우상): Spring Boot
if $SKIP_SPRING; then
  tmux send-keys -t "$SESSION:0.2" "echo '── Spring Boot (:8080) — 이미 실행 중 ──'; bash" Enter
else
  tmux send-keys -t "$SESSION:0.2" "echo '── Spring Boot (:8080) ──'; cd $MINDGRAPH_DIR/MindGraph && ./gradlew bootRun 2>&1" Enter
  wait_for "Spring Boot" "curl -s http://localhost:8080/api/graph/nodes | grep -q nodes" 120
fi

# 패널 1 (좌하): Vite UI
if $SKIP_VITE; then
  tmux send-keys -t "$SESSION:0.1" "echo '── Vite UI (:5173) — 이미 실행 중 ──'; bash" Enter
else
  tmux send-keys -t "$SESSION:0.1" "echo '── Vite UI (:5173) ──'; cd $MINDGRAPH_DIR/mindgraph-ui && npm run dev 2>&1" Enter
  wait_for "Vite UI" "curl -s http://localhost:5173 | grep -q html" 30
fi

# 패널 3 (우하): 대시보드 — 별도 스크립트로 분리
tmux send-keys -t "$SESSION:0.3" "bash $MINDGRAPH_DIR/.dashboard.sh" Enter

# ── 8. 브라우저 오픈 ─────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "전체 서비스 기동 완료!"
echo ""
echo "  🌐 UI:       http://localhost:5173"
echo "  🔧 Backend:  http://localhost:8080"
echo "  ⚡ LLM-OPT:  http://localhost:8000"
echo ""
echo "  tmux 재접속: tmux attach -t mindgraph"
echo "  종료:        ./stop.sh"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

if command -v wslview &>/dev/null; then
  wslview http://localhost:5173
elif command -v explorer.exe &>/dev/null; then
  explorer.exe http://localhost:5173
fi

tmux attach -t "$SESSION"
