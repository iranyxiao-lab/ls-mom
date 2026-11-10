-- modernize-ui-ux 4.2：表单"记住上次填写"的值，按表单编码保存，同一用户换设备也生效
alter table ui_user_pref add column if not exists remembered jsonb not null default '{}'::jsonb;
comment on column ui_user_pref.remembered is '记住上次填写：{表单编码: {values: {字段: 值}, at: 毫秒}}，只保存表单中 remember=true 的字段';
