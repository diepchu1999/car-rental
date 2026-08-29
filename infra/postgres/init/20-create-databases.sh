#!/usr/bin/env bash

set -Eeuo pipefail

required_variables=(
  POSTGRES_USER
  POSTGRES_DB
  POSTGRES_APP_DB
  POSTGRES_APP_USER
  POSTGRES_APP_PASSWORD
  KEYCLOAK_DB
  KEYCLOAK_DB_USER
  KEYCLOAK_DB_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    printf 'Missing required environment variable: %s\n' "$variable_name" >&2
    exit 1
  fi
done

if [[ "$POSTGRES_APP_DB" == "$KEYCLOAK_DB" ]]; then
  printf 'POSTGRES_APP_DB and KEYCLOAK_DB must be different.\n' >&2
  exit 1
fi

if [[ "$POSTGRES_APP_USER" == "$KEYCLOAK_DB_USER" ]]; then
  printf 'POSTGRES_APP_USER and KEYCLOAK_DB_USER must be different.\n' >&2
  exit 1
fi

if [[ "$POSTGRES_APP_DB" == "$POSTGRES_DB" || "$KEYCLOAK_DB" == "$POSTGRES_DB" ]]; then
  printf 'Application databases must differ from the maintenance database.\n' >&2
  exit 1
fi

if [[ "$POSTGRES_APP_USER" == "$POSTGRES_USER" || "$KEYCLOAK_DB_USER" == "$POSTGRES_USER" ]]; then
  printf 'Service users must differ from the PostgreSQL administrator.\n' >&2
  exit 1
fi

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=app_db="$POSTGRES_APP_DB" \
  --set=app_user="$POSTGRES_APP_USER" \
  --set=app_password="$POSTGRES_APP_PASSWORD" \
  --set=keycloak_db="$KEYCLOAK_DB" \
  --set=keycloak_user="$KEYCLOAK_DB_USER" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L',
  :'app_user',
  :'app_password'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_roles
  WHERE rolname = :'app_user'
)
\gexec

SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L',
  :'keycloak_user',
  :'keycloak_password'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_roles
  WHERE rolname = :'keycloak_user'
)
\gexec

SELECT format(
  'CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0',
  :'app_db',
  :'app_user',
  'UTF8'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_database
  WHERE datname = :'app_db'
)
\gexec

SELECT format(
  'ALTER DATABASE %I SET search_path TO %I, public',
  :'app_db',
  '$user'
)
\gexec

SELECT format(
  'CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0',
  :'keycloak_db',
  :'keycloak_user',
  'UTF8'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM pg_database
  WHERE datname = :'keycloak_db'
)
\gexec
SQL

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_APP_DB" \
  --set=ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
SQL