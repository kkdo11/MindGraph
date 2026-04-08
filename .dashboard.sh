#!/bin/bash
source /home/kdw03/projects/mindgraph-ai/.env 2>/dev/null
CHAT_MODEL="${CHAT_MODEL:-qwen2.5:14b}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-bge-m3}"

clear
cat << EOF
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   🧠  MindGraph 실행 중
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  🌐 UI        http://localhost:5173
  🔧 Backend   http://localhost:8080
  ⚡ LLM-OPT   http://localhost:8000
  🐇 RabbitMQ  http://localhost:15672  (guest/guest)
  🗄️  Redis UI  http://localhost:8001
  🔮 Neo4j     http://localhost:7474

  Chat:  $CHAT_MODEL
  Embed: $EMBEDDING_MODEL

  패널 이동: Ctrl+B 방향키
  전체화면:  Ctrl+B z
  종료:      ./stop.sh
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EOF
exec bash
