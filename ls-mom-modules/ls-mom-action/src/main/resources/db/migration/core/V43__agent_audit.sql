-- Agent 调用审计：批量草稿批次、审批单
alter table op_audit add column batch_id varchar(64);
alter table op_audit add column approval_id varchar(64);
create index idx_op_audit_batch on op_audit (batch_id) where batch_id is not null;
comment on column op_audit.batch_id is 'Agent 批量草稿的批次号';
comment on column op_audit.approval_id is 'APPROVAL_REQUIRED 级调用使用的一次性审批单';
