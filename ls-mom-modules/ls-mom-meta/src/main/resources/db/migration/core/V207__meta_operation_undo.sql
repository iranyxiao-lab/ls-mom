alter table meta_operation add column undo_operation varchar(160);
comment on column meta_operation.undo_operation is '撤销本动作的补偿 Operation；为空表示不可撤销，界面执行前须确认';
