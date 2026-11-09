-- 首次初始化数据库时执行：向量检索扩展与流程引擎独立 schema
create extension if not exists vector;
create schema if not exists flowable;
