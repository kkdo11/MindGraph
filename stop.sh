#!/bin/bash
# MindGraph 전체 종료 스크립트

MINDGRAPH_DIR="/home/kdw03/projects/mindgraph-ai"
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[✓]${NC} $1"; }
info() { echo -e "${YELLOW}[→]${NC} $1"; }

# .env 로드
[ -f "$MINDGRAPH_DIR/.env" ] && source "$MINDGRAPH_DIR/.env"
CHAT_MODEL="${CHAT_MODEL:-qwen2.5:14b}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-bge-m3}"
OLLAMA_PORT="${OLLAMA_PORT:-11434}"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "   🧠  MindGraph 종료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 1. VRAM 언로드
info "VRAM에서 모델 언로드 중..."
for model in "$CHAT_MODEL" "$EMBEDDING_MODEL"; do
  curl -s -X POST "http://localhost:${OLLAMA_PORT}/api/generate" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${model}\",\"keep_alive\":0}" > /dev/null 2>&1 \
    && echo "  → $model 언로드 완료" \
    || echo "  → $model 언로드 스킵"
done
log "VRAM 정리 완료"

# 2. tmux 세션 종료 (LLM-OPT, Spring Boot, Vite 포함)
info "서비스 프로세스 종료 중..."
tmux kill-session -t mindgraph 2>/dev/null && log "프로세스 종료 완료" || log "실행 중인 세션 없음"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log "종료 완료"
echo ""
echo "  Docker 인프라는 유지됩니다."
echo "  완전 종료: docker compose down"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
