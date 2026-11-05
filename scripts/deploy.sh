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

deadline=$(( $(date +%s) + 120 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    remaining=$(( deadline - $(date +%s) ))
    if curl --fail --silent --connect-timeout 2 --max-time "$remaining" http://127.0.0.1:8080/api/health; then
        echo
        echo "배포 성공: commit=$commit"
        exit 0
    fi
    sleep 5
done

echo "배포 실패: /api/health가 2분 안에 응답하지 않았습니다." >&2
exit 1
