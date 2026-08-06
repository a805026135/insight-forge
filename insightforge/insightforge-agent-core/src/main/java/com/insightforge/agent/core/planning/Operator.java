package com.insightforge.agent.core.planning;

/**
 * 护栏 DSL 的比较算子（大白话：阈值怎么比）。
 * INVALID 兜底：未知算子编译期判非法。
 */
public enum Operator {
    LT, LE, GT, GE, EQ, INVALID
}
