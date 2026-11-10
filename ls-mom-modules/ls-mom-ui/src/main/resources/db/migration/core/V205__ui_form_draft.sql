-- modernize-ui-ux 4.3：表单草稿自动保存（尚未提交的录入内容），关闭浏览器、断网或会话过期后可恢复
create table if not exists ui_form_draft
(
    user_id    bigint       not null,
    draft_key  varchar(200) not null,
    content    jsonb        not null,
    hash       varchar(64)  not null,
    updated_at timestamp    not null default current_timestamp,
    primary key (user_id, draft_key)
);
comment on table ui_form_draft is '表单草稿：draft_key 为 表单或 Concept 编码 + 记录 id（新单据为 new），content 为录入内容，hash 用于去重';
