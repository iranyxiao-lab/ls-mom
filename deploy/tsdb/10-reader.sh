#!/bin/sh
# 业务系统读取 Historian 的只读账号：只有查询权限（含模拟器之后建的表）
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v pw="$HISTORIAN_READER_PASSWORD" <<'SQL'
create role lsmom_reader login password :'pw';
grant connect on database lsmom_ts to lsmom_reader;
grant usage on schema public to lsmom_reader;
grant select on all tables in schema public to lsmom_reader;
alter default privileges in schema public grant select on tables to lsmom_reader;
SQL
