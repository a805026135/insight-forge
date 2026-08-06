package com.insightforge.rule.engine;

/**
 * 护栏命中风险分级（评估方案 §5：查询类低 / 写入类中 / 跨活动高）。
 *
 * 大白话：规则引擎每触发一次，都要标个危险等级——
 * - LOW：只读/告警（ALERT），不写共享状态，最安全；
 * - MEDIUM：写入类（REPRICE 调价 / REALLOCATE 调拨），动库存/价格，必须走锁；
 * - HIGH：跨活动预算再分配，影响面大，须人工审批（本 Phase 用参数标记，留 HITL 接口）。
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
