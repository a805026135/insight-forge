-- H2 专用：V6 新增表（merchant/user_address/cart_item/order_item）在 MySQL 会自动推进自增，
-- H2 需要显式 RESTART，避免后续 INSERT 主键冲突。
ALTER TABLE merchant     ALTER COLUMN id RESTART WITH 100;
ALTER TABLE user_address ALTER COLUMN id RESTART WITH 100;
ALTER TABLE cart_item    ALTER COLUMN id RESTART WITH 100;
ALTER TABLE order_item   ALTER COLUMN id RESTART WITH 100;
