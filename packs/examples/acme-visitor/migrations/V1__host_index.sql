-- 门卫按被访人查询：补充索引（台账表由平台根据 concepts/ 生成，表名 c_<命名空间>_<对象>）
create index if not exists idx_acme_visitor_visit_host on c_acme_visitor_visit (host_name);
