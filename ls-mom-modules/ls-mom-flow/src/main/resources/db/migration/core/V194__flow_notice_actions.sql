-- modernize-ui-ux 3.7：通知抽屉——通知带标题、跳转地址、可在抽屉内完成的简单动作；新增类型：需要确认（ACTION）、系统消息（SYSTEM）、@我（MENTION）
alter table biz_flow_notice add column if not exists title varchar(200);
alter table biz_flow_notice add column if not exists link varchar(500);
alter table biz_flow_notice add column if not exists action jsonb;
alter table biz_flow_notice add column if not exists done_at timestamp;
comment on column biz_flow_notice.action is '抽屉内可直接完成的动作：{operation, concept, targetId, label}';
comment on column biz_flow_notice.done_at is '动作已完成（本人或他人办理）的时间';

-- 以后再改类型约束时须保留全部已有类型
alter table biz_flow_notice drop constraint if exists biz_flow_notice_kind_check;
alter table biz_flow_notice add constraint biz_flow_notice_kind_check
    check (kind in ('REMIND', 'URGE', 'CC', 'ESCALATE', 'EXCEPTION', 'TRANSFER', 'PACK_REVIEW', 'DCS_ALARM', 'ACTION', 'SYSTEM', 'MENTION'));
create index if not exists idx_biz_flow_notice_biz on biz_flow_notice (biz_type, biz_id) where done_at is null;
