-- 常用查询/对账索引（订单按用户、按活动×SKU；SKU 按活动；trace 按活动）
CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_activity_sku ON orders (activity_id, sku_id);
CREATE INDEX idx_activity_sku_activity ON activity_sku (activity_id);
CREATE INDEX idx_agent_trace_activity ON agent_trace (activity_id);
