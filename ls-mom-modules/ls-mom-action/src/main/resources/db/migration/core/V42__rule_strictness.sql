-- 规则发布：来源 Pack、是否标准规则、覆盖关系（客户规则只能在标准规则之上加严）
alter table meta_rule
    add column owner_pack   varchar(64),
    add column standard     boolean   not null default false,
    add column overrides    varchar(160),
    add column published_at timestamp;
comment on column meta_rule.standard is '标准 Pack 声明的规则；声明为阻断（L1～L4）的标准规则不能被关闭或降为提示';
comment on column meta_rule.overrides is '加严覆盖的标准规则编码；求值时与被覆盖规则同时执行（叠加，不替换）';
create unique index uq_meta_rule_published on meta_rule (code) where status = 'PUBLISHED';
create index idx_meta_rule_overrides on meta_rule (overrides) where status = 'PUBLISHED';
