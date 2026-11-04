#!/bin/sh
set -eu

if [ -e .env ]; then
    echo "설정 중단: .env 파일이 이미 있습니다." >&2
    exit 1
fi

umask 077
printf '%s\n' \
    'STORAGE_TYPE=s3' \
    'S3_BUCKET=hackathon-e3-t09-data' \
    'S3_PREFIX=uploads/' \
    'AWS_REGION=ap-southeast-1' \
    'DEMO_SEED=true' \
    'CORS_ORIGINS=http://localhost:3000' > .env
openssl rand -base64 32 | sed 's/^/DB_PASSWORD=/' >> .env
openssl rand -base64 32 | sed 's/^/JWT_SECRET=/' >> .env
openssl rand -base64 32 | sed 's/^/INTERNAL_SECRET=/' >> .env
openssl rand -base64 32 | sed 's/^/DEMO_PASSWORD=/' >> .env
chmod 600 .env
echo "서버 전용 .env 파일을 만들었습니다. 값은 출력하지 않습니다."
