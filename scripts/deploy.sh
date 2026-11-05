#!/bin/sh
set -eu

target=${1:?Usage: scripts/deploy.sh <branch-or-commit>}
if [ ! -f .env ]; then
    echo "배포 중단: 서버의 .env 파일이 필요합니다." >&2
    exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
    echo "배포 중단: 작업 트리에 커밋되지 않은 변경이 있습니다." >&2
    exit 1
fi

git fetch origin
if git show-ref --verify --quiet "refs/remotes/origin/$target"; then
    git checkout "$target"
    git pull --ff-only origin "$target"
else
    git checkout "$target"
fi

commit=$(git rev-parse --short HEAD)
GIT_COMMIT="$commit" docker compose -f compose.prod.yaml up -d --build
docker compose -f compose.prod.yaml restart api-gateway

deadline=$(( $(date +%s) + 120 ))
app_ok=0
gateway_ok=0
while [ "$(date +%s)" -lt "$deadline" ]; do
    app_ok=0
    gateway_ok=0
    if curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:18080/api/health >/dev/null; then
        app_ok=1
    fi
    if curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:8080/api/health >/dev/null; then
        gateway_ok=1
    fi
    if [ "$app_ok" -eq 1 ] && [ "$gateway_ok" -eq 1 ]; then
        curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:8080/api/health
        echo
        echo "배포 성공: commit=$commit"
        exit 0
    fi
    sleep 5
done

if [ "$app_ok" -ne 1 ] && [ "$gateway_ok" -ne 1 ]; then
    echo "배포 실패: 앱 직접 헬스체크(127.0.0.1:18080)와 게이트웨이 헬스체크(127.0.0.1:8080)가 2분 안에 성공하지 않았습니다." >&2
elif [ "$app_ok" -ne 1 ]; then
    echo "배포 실패: 앱 직접 헬스체크(127.0.0.1:18080)가 2분 안에 성공하지 않았습니다." >&2
else
    echo "배포 실패: 게이트웨이 헬스체크(127.0.0.1:8080)가 2분 안에 성공하지 않았습니다." >&2
fi
exit 1
