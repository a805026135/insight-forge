package com.insightforge.agent.core.planning;

/**
 * 一个子任务（Planner 拆 DAG 后的单元）。
 * 大白话：总规划把「定双11预案」拆成「选品 / 定价 / 预热 / 风控」四个小任务，各交给对应 Agent。
 */
public record SubTask(Role role, String goalFragment, Long activityId) {
}
