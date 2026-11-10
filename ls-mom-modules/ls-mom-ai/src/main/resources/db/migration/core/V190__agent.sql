-- 运行期 Agent：服务身份、API Key、审批单、密钥事件
create table agent_identity
(
    id                    bigserial    primary key,
    code                  varchar(64)  not null unique,
    name                  varchar(100) not null,
    party_id              bigint       not null references core_party (id),
    permissions           jsonb        not null default '[]',
    rate_limit_per_minute int          not null default 60 check (rate_limit_per_minute between 1 and 10000),
    status                varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    description           varchar(500),
    created_by            bigint,
    created_at            timestamp    not null default current_timestamp,
    updated_at            timestamp    not null default current_timestamp
);
comment on table agent_identity is 'Agent 服务身份：自身权限上限（与代理的真人权限取交集）与限流';

create table agent_api_key
(
    id            bigserial   primary key,
    agent_id      bigint      not null references agent_identity (id),
    prefix        varchar(16) not null unique,
    key_hash      char(64)    not null,
    status        varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
    expires_at    timestamp   not null,
    rotated_to    bigint references agent_api_key (id),
    created_by    bigint,
    created_at    timestamp   not null default current_timestamp,
    revoked_at    timestamp,
    revoked_by    bigint,
    revoke_reason varchar(200),
    last_used_at  timestamp
);
create index idx_agent_api_key_agent on agent_api_key (agent_id);
comment on table agent_api_key is 'Agent API Key：只存 SHA-256 摘要，明文只在签发时返回一次';

create table agent_key_event
(
    id          bigserial   primary key,
    occurred_at timestamp   not null default current_timestamp,
    prefix      varchar(16),
    agent_id    bigint,
    event       varchar(24) not null check (event in ('ISSUED', 'ROTATED', 'REVOKED', 'INVALID', 'EXPIRED_USE', 'REVOKED_USE',
                                                     'RATE_LIMITED', 'DISABLED_USE')),
    actor_party bigint,
    detail      varchar(500)
);
create index idx_agent_key_event_agent on agent_key_event (agent_id, occurred_at);
comment on table agent_key_event is 'API Key 签发、轮换、吊销与被拒绝的使用（只追加）';

create table agent_approval
(
    id              varchar(64)  primary key,
    agent_id        bigint       not null references agent_identity (id),
    on_behalf_of    bigint       not null,
    op_code         varchar(160) not null,
    target_id       varchar(64),
    target_version  int,
    input           jsonb        not null,
    input_digest    char(64)     not null,
    reason          varchar(500),
    status          varchar(16)  not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'USED')),
    approved_by     bigint,
    approved_at     timestamp,
    expires_at      timestamp,
    used_at         timestamp,
    note            varchar(500),
    trace_id        varchar(64),
    created_at      timestamp    not null default current_timestamp
);
create index idx_agent_approval_status on agent_approval (status, created_at);
comment on table agent_approval is 'APPROVAL_REQUIRED 级调用的一次性审批单：绑定 Operation、输入摘要、业务版本、批准人与有效期';
