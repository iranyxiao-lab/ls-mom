-- 按访问人查询拒绝记录（越权审计、安全复核）
create index if not exists idx_core_access_audit_actor on core_access_audit (actor_party, occurred_at desc);
