create table t_lube_log (
  id bigint primary key,
  equip_no varchar(40) not null, -- 设备编号
  oil_type varchar(20) not null, -- 油品
  volume numeric(8,2), -- 加注量
  lubed_at timestamp not null, -- 润滑时间
  created_at timestamp
);
