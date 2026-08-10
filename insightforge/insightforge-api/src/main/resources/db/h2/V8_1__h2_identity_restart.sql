-- H2 专用：V8 无固定 id 插入，仅需保证 product 表结构同步（RESTART 可选）
ALTER TABLE product ALTER COLUMN id RESTART WITH 43;
