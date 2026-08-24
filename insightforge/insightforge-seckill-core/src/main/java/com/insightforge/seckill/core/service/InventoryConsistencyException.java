package com.insightforge.seckill.core.service;

/**
 * Redis 已预留但数据库库存无法收敛时抛出。事务必须回滚，预留凭证保留给补偿/对账处理。
 */
public class InventoryConsistencyException extends RuntimeException {
    public InventoryConsistencyException(String message) {
        super(message);
    }
}
