-- H2 专用：显式 id 插入后自增计数不推进（MySQL 会自动推进），必须手动 RESTART。
-- 该文件只挂在 local profile 的 flyway locations 里，MySQL 不会执行。
ALTER TABLE product      ALTER COLUMN id RESTART WITH 3;
ALTER TABLE activity     ALTER COLUMN id RESTART WITH 3;
ALTER TABLE activity_sku ALTER COLUMN id RESTART WITH 3;
ALTER TABLE users        ALTER COLUMN id RESTART WITH 4;
