package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AgentPromptVersion;
import com.insightforge.persistence.mapper.AgentPromptVersionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/** Prompt 内容与版本的审计注册表。 */
@Service
public class PromptVersionService {
    private final AgentPromptVersionMapper mapper;
    private final String activeVersion;

    public PromptVersionService(AgentPromptVersionMapper mapper,
                                @Value("${insightforge.agent.prompt-version:insightforge-prompt-1.0}") String activeVersion) {
        this.mapper = mapper;
        this.activeVersion = activeVersion;
    }

    public AgentPromptVersion register(String key, String content) {
        String version = activeVersion + "-" + key;
        AgentPromptVersion found = mapper.selectOne(new QueryWrapper<AgentPromptVersion>()
                .eq("prompt_key", key).eq("version", version));
        if (found != null) return found;
        AgentPromptVersion row = new AgentPromptVersion();
        row.setPromptKey(key); row.setVersion(version); row.setContent(content);
        row.setChecksum(sha256(content)); row.setStatus("ACTIVE"); row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
        return row;
    }

    public List<AgentPromptVersion> list() {
        return mapper.selectList(new QueryWrapper<AgentPromptVersion>().orderByDesc("created_at"));
    }

    private String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
