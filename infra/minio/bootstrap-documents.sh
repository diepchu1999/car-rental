#!/bin/sh
# BR-130: chạy bằng docker compose exec -T minio sh < file này. Không bật shell trace.
set +x
set -eu
umask 077

fail() { printf '%s\n' 'Document storage bootstrap failed; check required configuration and MinIO availability.' >&2; exit 1; }
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${CAR_RENTAL_STORAGE_BUCKET:?CAR_RENTAL_STORAGE_BUCKET is required}"
: "${CAR_RENTAL_STORAGE_ACCESS_KEY:?CAR_RENTAL_STORAGE_ACCESS_KEY is required}"
: "${CAR_RENTAL_STORAGE_SECRET_KEY:?CAR_RENTAL_STORAGE_SECRET_KEY is required}"
: "${CAR_RENTAL_STORAGE_BOOTSTRAP_ENDPOINT:?CAR_RENTAL_STORAGE_BOOTSTRAP_ENDPOINT is required}"

# Tên bucket/access key được dùng trong policy và tham số mc: không nhận ký tự điều khiển/JSON.
case "$CAR_RENTAL_STORAGE_BUCKET" in *[!a-z0-9-]*|-*|*-|'') fail ;; esac
[ "${#CAR_RENTAL_STORAGE_BUCKET}" -ge 3 ] && [ "${#CAR_RENTAL_STORAGE_BUCKET}" -le 63 ] || fail
case "$CAR_RENTAL_STORAGE_ACCESS_KEY" in *[!A-Za-z0-9-]*|'') fail ;; esac
[ "$CAR_RENTAL_STORAGE_ACCESS_KEY" != "$MINIO_ROOT_USER" ] || fail
[ "${#CAR_RENTAL_STORAGE_SECRET_KEY}" -ge 16 ] || fail

storage_bootstrap_dir=$(mktemp -d /tmp/car-rental-minio.XXXXXXXX) || fail
# Chỉ xoá thư mục vừa được mktemp tạo, không dùng đường dẫn người dùng cung cấp.
trap 'rm -rf -- "$storage_bootstrap_dir"' EXIT HUP INT TERM

mc --config-dir "$storage_bootstrap_dir" alias set setup "$CAR_RENTAL_STORAGE_BOOTSTRAP_ENDPOINT" \
    "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 || fail
mc --config-dir "$storage_bootstrap_dir" mb --ignore-existing "setup/$CAR_RENTAL_STORAGE_BUCKET" >/dev/null 2>&1 || fail
mc --config-dir "$storage_bootstrap_dir" anonymous set none "setup/$CAR_RENTAL_STORAGE_BUCKET" >/dev/null 2>&1 || fail

# Không cho xoá object, quản trị bucket, liệt kê bucket khác hoặc thay policy.
cat > "$storage_bootstrap_dir/policy.json" <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect":"Allow","Action":["s3:GetBucketLocation"],"Resource":["arn:aws:s3:::$CAR_RENTAL_STORAGE_BUCKET"]},
    {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject"],"Resource":["arn:aws:s3:::$CAR_RENTAL_STORAGE_BUCKET/incoming/*","arn:aws:s3:::$CAR_RENTAL_STORAGE_BUCKET/sealed/*"]}
  ]
}
POLICY
mc --config-dir "$storage_bootstrap_dir" admin user add setup "$CAR_RENTAL_STORAGE_ACCESS_KEY" \
    "$CAR_RENTAL_STORAGE_SECRET_KEY" >/dev/null 2>&1 || fail
mc --config-dir "$storage_bootstrap_dir" admin policy create setup car-rental-document-objects \
    "$storage_bootstrap_dir/policy.json" >/dev/null 2>&1 || fail
mc --config-dir "$storage_bootstrap_dir" admin policy attach setup car-rental-document-objects \
    --user "$CAR_RENTAL_STORAGE_ACCESS_KEY" >/dev/null 2>&1 || fail
printf '%s\n' 'Private document bucket and application account are ready.'
