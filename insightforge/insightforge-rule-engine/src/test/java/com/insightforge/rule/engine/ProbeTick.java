package com.insightforge.rule.engine;

/**
 * Drools 探针用的简单事实类（仅用于验证 Drools 在 Java 21 可运行）。
 */
public class ProbeTick {
    private final double stockRatio;
    private String flag;

    public ProbeTick(double stockRatio) {
        this.stockRatio = stockRatio;
    }

    public double getStockRatio() {
        return stockRatio;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }
}
