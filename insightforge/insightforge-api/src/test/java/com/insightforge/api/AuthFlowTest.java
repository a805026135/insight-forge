package com.insightforge.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 14.3 鉴权流程测试：
 *  - 登录返回 JWT（BCrypt 密码校验）；
 *  - 无 token 访问受保护接口 → 401；
 *  - 买家 token 访问商家专属接口 → 403；
 *  - 秒杀/订单以登录身份为准（伪造他人 userId 无效）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional  // 每个用例回滚，避免污染共享 H2（否则会干扰 NoOversell 的全局订单计数）
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String login(String username, String password) throws Exception {
        var res = mockMvc.perform(post("/api/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    @Test
    void unauthenticatedRequestsRejected() throws Exception {
        mockMvc.perform(get("/api/activity"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/seckill")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void buyerCannotAccessMerchantEndpoints() throws Exception {
        String buyerToken = login("user1", "123456");
        // 活动列表：任何登录用户可读
        mockMvc.perform(get("/api/activity").header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk());
        // Agent 规划：商家专属 → 403
        mockMvc.perform(post("/api/agent/plan")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"activityId\":1,\"goal\":\"x\"}"))
                .andExpect(status().isForbidden());
        // 预热：商家专属 → 403
        mockMvc.perform(post("/api/seckill/admin/preheat/1")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void seckillAndOrdersUseTokenIdentity() throws Exception {
        String buyerToken = login("user1", "123456");
        // 请求体 userId 写 99999，但实际以登录身份（user1, id=2）下单
        mockMvc.perform(post("/api/seckill")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"activityId\":1,\"skuId\":1,\"userId\":99999,\"qty\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // user1 能看到自己的订单
        mockMvc.perform(get("/api/order?userId=2")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", not(org.hamcrest.Matchers.empty())));
        // 伪造 userId=99999 查别人订单 → 业务 403（项目惯例：HTTP 200 + code=403）
        mockMvc.perform(get("/api/order?userId=99999")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
