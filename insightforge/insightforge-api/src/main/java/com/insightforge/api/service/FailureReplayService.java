package com.insightforge.api.service;

import com.insightforge.agent.core.evaluation.EvaluationHarness;
import com.insightforge.agent.core.evaluation.EvaluationReport;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** 生产模型回放可能超过网关超时，因此以异步任务运行并轮询结果。 */
@Service
public class FailureReplayService {
    private final EvaluationHarness harness;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FailureReplayService(EvaluationHarness harness) { this.harness = harness; }

    public Job start() {
        String id = UUID.randomUUID().toString();
        Job queued = new Job(id, "RUNNING", Instant.now().toString(), null, List.of(), null);
        jobs.put(id, queued);
        executor.submit(() -> {
            try {
                List<EvaluationReport.RegressionResult> result = harness.replayFailureSamples();
                jobs.put(id, new Job(id, "COMPLETED", queued.startedAt(), Instant.now().toString(), result, null));
            } catch (Exception e) {
                jobs.put(id, new Job(id, "FAILED", queued.startedAt(), Instant.now().toString(), List.of(), e.getMessage()));
            }
        });
        return queued;
    }

    public Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("回放任务不存在");
        return job;
    }

    public record Job(String jobId, String status, String startedAt, String finishedAt,
                      List<EvaluationReport.RegressionResult> results, String error) {}
}
