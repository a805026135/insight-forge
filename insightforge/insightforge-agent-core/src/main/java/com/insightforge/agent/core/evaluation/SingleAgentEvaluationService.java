package com.insightforge.agent.core.evaluation;

import com.insightforge.agent.core.planning.GoldenEvalHarness;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class SingleAgentEvaluationService {
    private final SingleAgentEvaluator evaluator;
    private final GoldenEvalHarness golden;

    public SingleAgentEvaluationService(SingleAgentEvaluator evaluator, GoldenEvalHarness golden) {
        this.evaluator = evaluator;
        this.golden = golden;
    }

    public Report run() throws Exception {
        List<CaseResult> rows = new ArrayList<>();
        for (var c : golden.loadCases()) {
            var r = evaluator.run(c.activityId(), c.goal(), false);
            rows.add(new CaseResult(c.name(), false, "SUCCESS".equals(r.status()), r));
        }
        for (var c : FailureSample.load()) {
            var r = evaluator.run(c.activityId(), c.goal(), true);
            rows.add(new CaseResult(c.name(), true, "CORRECT_FAILURE".equals(r.status()), r));
        }
        List<Long> latency = rows.stream().map(x -> x.result().latencyMs()).sorted().toList();
        int positive = (int) rows.stream().filter(x -> !x.safety()).count();
        int safe = (int) rows.stream().filter(CaseResult::safety).count();
        int positivePassed = (int) rows.stream().filter(x -> !x.safety() && x.passed()).count();
        int safePassed = (int) rows.stream().filter(x -> x.safety() && x.passed()).count();
        int tokens = rows.stream().mapToInt(x -> x.result().tokens()).sum();
        BigDecimal cost = rows.stream().map(x -> x.result().costUsd()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(positive, positivePassed, safe, safePassed,
                percentile(latency, .50), percentile(latency, .95), tokens, cost, rows);
    }

    private long percentile(List<Long> values, double p) {
        return values.get(Math.max(0, (int) Math.ceil(values.size() * p) - 1));
    }

    public record CaseResult(String name, boolean safety, boolean passed, SingleAgentEvaluator.Result result) {}
    public record Report(int positiveTasks, int positivePassed, int safetyTasks, int safetyPassed,
                         long p50LatencyMs, long p95LatencyMs, int totalTokens,
                         BigDecimal totalCostUsd, List<CaseResult> cases) {}
}
