#!/bin/sh
# AI 연동 모드를 전환하고(.env의 AI_MODE·AI_BASE_URL만 수정) 잠금 배포까지 실행한다.
set -eu

mode=${1:-}
base_url=${2:-}

usage() {
    echo "Usage: scripts/switch-ai-mode.sh <mock|http> [base-url]" >&2
    echo "  scripts/switch-ai-mode.sh http http://host.docker.internal:8000" >&2
    echo "  scripts/switch-ai-mode.sh mock" >&2
}

if [ "$mode" != "mock" ] && [ "$mode" != "http" ]; then
    usage
    exit 1
fi
if [ "$mode" = "http" ] && [ -z "$base_url" ]; then
    echo "오류: http 모드는 base-url 인자가 필요합니다." >&2
    usage
    exit 1
fi
if [ ! -f .env ]; then
    echo "중단: 저장소 루트에 .env 파일이 없습니다." >&2
    exit 1
fi

backup=".env.bak.$(date +%Y%m%d%H%M%S)"
cp .env "$backup"
chmod 600 "$backup"

set_env_value() {
    key=$1
    value=$2
    if grep -q "^${key}=" .env; then
        tmp=$(mktemp .env.tmp.XXXXXX)
        awk -F= -v k="$key" -v v="$value" 'BEGIN{OFS="="} $1==k{$0=k"="v} {print}' .env > "$tmp"
        mv "$tmp" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
}

set_env_value AI_MODE "$mode"
if [ "$mode" = "http" ]; then
    set_env_value AI_BASE_URL "$base_url"
fi
chmod 600 .env

echo "AI_MODE를 ${mode}로 전환했습니다(.env 백업: ${backup}). 잠금 배포를 시작합니다."
flock /tmp/docs199-deploy.lock scripts/deploy.sh develop
