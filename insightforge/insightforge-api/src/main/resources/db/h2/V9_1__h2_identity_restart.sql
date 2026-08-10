-- H2 专用：V9 无新固定 id，仅同步结构
ALTER TABLE cart_item ALTER COLUMN id RESTART WITH 100;
