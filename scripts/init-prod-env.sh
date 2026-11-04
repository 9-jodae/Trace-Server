#!/bin/sh
set -eu

if [ -e .env ]; then
    echo "설정 중단: .env 파일이 이미 있습니다." >&2
    exit 1
fi

umask 077
db_password=$(openssl rand -hex 32) || exit 1
jwt_secret=$(openssl rand -hex 32) || exit 1
internal_secret=$(openssl rand -hex 32) || exit 1
demo_password=$(openssl rand -hex 32) || exit 1
if [ -z "$db_password" ] || [ -z "$jwt_secret" ] || [ -z "$internal_secret" ] || [ -z "$demo_password" ]; then
    echo "설정 중단: 비밀값 생성에 실패했습니다." >&2
    exit 1
fi

temp=$(mktemp .env.tmp.XXXXXX)
trap 'rm -f "$temp"' EXIT
printf '%s\n' \
    'STORAGE_TYPE=s3' \
    'S3_BUCKET=hackathon-e3-t09-data' \
    'S3_PREFIX=uploads/' \
    'AWS_REGION=ap-southeast-1' \
    'DEMO_SEED=true' \
    'CORS_ORIGINS=http://localhost:3000' \
    "DB_PASSWORD=$db_password" \
    "JWT_SECRET=$jwt_secret" \
    "INTERNAL_SECRET=$internal_secret" \
    "DEMO_PASSWORD=$demo_password" > "$temp"
mv "$temp" .env
trap - EXIT
chmod 600 .env
echo "서버 전용 .env 파일을 만들었습니다. 값은 출력하지 않습니다."
