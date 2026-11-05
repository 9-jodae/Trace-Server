#!/usr/bin/env python3
"""현재 문서 버전의 S3 원문을 AI 일괄 적재 JSON으로 내보낸다."""

import argparse
from collections import Counter
from io import BytesIO
import json
import os
from pathlib import Path
import sys
import tempfile

import boto3
from dotenv import dotenv_values
import psycopg
from pypdf import PdfReader


def settings():
    values = {key: value for key, value in dotenv_values(".env").items() if value is not None}
    values.update(os.environ)
    return values


def connection(values):
    url = values.get("DATABASE_URL")
    if url:
        return psycopg.connect(url, connect_timeout=10)
    password = values.get("DB_PASSWORD")
    if not password:
        raise ValueError("DATABASE_URL 또는 DB_PASSWORD가 필요합니다.")
    return psycopg.connect(
        host=values.get("DB_HOST", "127.0.0.1"),
        port=int(values.get("DB_PORT", "5432")),
        dbname=values.get("DB_NAME", "docs199"),
        user=values.get("DB_USER", "docs199"),
        password=password,
        connect_timeout=10,
    )


def extract_text(extension, data):
    if extension in {"md", "txt"}:
        return data.decode("utf-8")
    if extension == "pdf":
        return "\n\n".join((page.extract_text() or "").strip() for page in PdfReader(BytesIO(data)).pages)
    raise ValueError("지원하지 않는 형식")


def write_json(path, documents):
    path = Path(path).expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            os.chmod(temporary, 0o600)
            json.dump(documents, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description="현재 문서 버전을 AI 적재 JSON으로 내보냅니다.")
    parser.add_argument("output", help="출력할 JSON 파일 경로")
    args = parser.parse_args()
    values = settings()
    bucket = values.get("S3_BUCKET")
    if not bucket:
        raise ValueError("S3_BUCKET이 필요합니다.")
    prefix = values.get("S3_PREFIX", "uploads/").strip("/")
    prefix = prefix + "/" if prefix else ""
    with connection(values) as conn:
        with conn.cursor() as cursor:
            cursor.execute(
                """SELECT d.id, v.id, d.title, d.extension, v.storage_key
                   FROM documents d JOIN document_versions v
                     ON v.document_id = d.id AND v.version_number = d.current_version
                   WHERE d.deleted_at IS NULL ORDER BY d.id"""
            )
            rows = cursor.fetchall()

    s3 = boto3.client("s3")
    documents = []
    counts = Counter()
    skipped = []
    empty = []
    for doc_id, version_id, title, extension, storage_key in rows:
        extension = extension.lower()
        if extension not in {"md", "txt", "pdf"}:
            skipped.append((str(doc_id), extension))
            continue
        try:
            response = s3.get_object(Bucket=bucket, Key=prefix + storage_key.lstrip("/"))
            with response["Body"] as stream:
                text = extract_text(extension, stream.read())
        except Exception as error:
            raise RuntimeError(f"문서 {doc_id} 처리 실패 ({type(error).__name__})") from None
        if not text.strip():
            empty.append(str(doc_id))
        documents.append({"doc_id": str(doc_id), "version_id": str(version_id), "title": title, "text": text})
        counts[extension] += 1
    print(f"대상 현재 문서: {len(rows)}개, 내보낸 문서: {len(documents)}개")
    print("형식별: " + ", ".join(f"{key} {counts[key]}개" for key in sorted(counts)))
    print("건너뛴 문서: " + (", ".join(f"{doc_id} ({ext})" for doc_id, ext in skipped) or "없음"))
    print("빈 본문 문서: " + (", ".join(empty) or "없음"))
    if skipped or empty:
        raise RuntimeError("누락 또는 빈 본문이 있어 전달 전 확인이 필요합니다.")
    write_json(args.output, documents)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"내보내기 실패: {type(error).__name__}", file=sys.stderr)
        if isinstance(error, (ValueError, RuntimeError)):
            print(str(error), file=sys.stderr)
        sys.exit(1)
