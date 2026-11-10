-- 从模板库 / Excel 推断生成的 Pack 草稿也作为 ChangeSet 提交
alter table meta_change_set drop constraint if exists meta_change_set_source_check;
alter table meta_change_set add constraint meta_change_set_source_check
    check (source in ('DESIGNER', 'DRIFT', 'AI', 'PACK', 'TEMPLATE', 'EXCEL'));
