#!/bin/sh
set -eu

if [ ! -f .env ]; then
    echo "활성화 중단: 서버의 .env 파일이 필요합니다." >&2
    exit 1
fi

ai_db_password=$(grep -E '^AI_DB_PASSWORD=' .env | head -1 | cut -d= -f2-)
if [ -z "$ai_db_password" ]; then
    echo "활성화 중단: .env에 AI_DB_PASSWORD가 없습니다." >&2
    exit 1
fi

docker compose -f compose.prod.yaml exec -T postgres \
    psql -U docs199 -d docs199 -v ON_ERROR_STOP=1 -v pw="$ai_db_password" >/dev/null <<'SQL'
ALTER ROLE ai_app LOGIN PASSWORD :'pw';
SQL

echo "ai_app 로그인을 활성화했습니다. 값은 출력하지 않습니다."
