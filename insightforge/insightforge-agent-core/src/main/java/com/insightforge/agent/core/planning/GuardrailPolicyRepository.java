package com.insightforge.agent.core.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.GuardrailPolicyEntity;
import com.insightforge.persistence.mapper.GuardrailPolicyMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 护栏策略仓库（大白话：把编排器产出、编译通过的 DSL「存档 + 上架」）。
 *
 * 两件事：
 *   1) 版本化持久化——每次发布 INSERT 一条（activity_id + version 唯一），版本号自增（v1/v2...）。
 *   2) 热更新缓存——写入 {@code guardrail:dsl:{activityId}}，Phase 9 规则引擎 Worker 直接读它秒级生效。
 *
 * Fail-Close 纪律：只有「编译通过 + Critic 三道校验通过」的 DSL 才会走到这里；
 * 不合规 DSL 在编排器里就被拦下，绝不进仓库（0 裸奔上线）。
 */
@Component
public class GuardrailPolicyRepository {

    private final GuardrailPolicyMapper mapper;
    private final PolicyCache cache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GuardrailPolicyRepository(GuardrailPolicyMapper mapper, PolicyCache cache) {
        this.mapper = mapper;
        this.cache = cache;
    }

    /** 发布一份 DSL：分配新版本、持久化、上架缓存，返回带真实版本的 DSL。 */
    public GuardrailPolicy publish(GuardrailPolicy p) {
        GuardrailPolicyEntity latest = mapper.selectLatestByActivity(p.activityId());
        int next = (latest == null) ? 1 : parseVersion(latest.getVersion()) + 1;
        String version = "v" + next;

        GuardrailPolicy withVersion = new GuardrailPolicy(p.activityId(), version, p.rules());
        GuardrailPolicyEntity e = new GuardrailPolicyEntity();
        e.setActivityId(p.activityId());
        e.setVersion(version);
        e.setPolicyJson(safeJson(withVersion));
        e.setCreatedAt(LocalDateTime.now());
        mapper.insert(e);

        cache.put(p.activityId(), withVersion);
        return withVersion;
    }

    /** 读某活动最新 DSL：先查缓存（热路径秒级），未命中再查库并回填缓存。 */
    public GuardrailPolicy getLatest(Long activityId) {
        GuardrailPolicy cached = cache.get(activityId);
        if (cached != null) {
            return cached;
        }
        GuardrailPolicyEntity e = mapper.selectLatestByActivity(activityId);
        if (e == null) {
            return null;
        }
        GuardrailPolicy p = fromJson(e.getPolicyJson());
        if (p != null) {
            cache.put(activityId, p);
        }
        return p;
    }

    private String safeJson(GuardrailPolicy p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            return "{}";
        }
    }

    private GuardrailPolicy fromJson(String json) {
        try {
            return objectMapper.readValue(json, GuardrailPolicy.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern VER = Pattern.compile("(\\d+)");

    private int parseVersion(String v) {
        if (v == null) {
            return 0;
        }
        Matcher m = VER.matcher(v);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
