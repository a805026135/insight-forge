package com.insightforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 17 商家隔离：商家只能看到/操作自己的商品，买家无法访问商家接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class MerchantIsolationTest {

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
    void merchantLoginCarriesMerchantId() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"huawei\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("merchant"))
                .andExpect(jsonPath("$.data.merchantId").value(2));
    }

    @Test
    void merchantOnlySeesOwnProducts() throws Exception {
        String token = login("huawei", "123456");
        var res = mockMvc.perform(get("/api/merchant/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        // 华为店铺应有多个商品，且全部 merchantId=2，不含 Apple 商品
        org.junit.jupiter.api.Assertions.assertTrue(data.size() >= 6, "华为应有至少 6 个商品");
        for (JsonNode node : data) {
            org.junit.jupiter.api.Assertions.assertEquals(2L, node.path("merchantId").asLong());
        }
        boolean hasAppleProduct = false;
        for (JsonNode node : data) {
            if (node.path("name").asText().contains("iPhone")) {
                hasAppleProduct = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(hasAppleProduct, "华为账号不应看到 Apple 商品");
    }

    @Test
    void buyerCannotAccessMerchantApi() throws Exception {
        String token = login("user1", "123456");
        mockMvc.perform(get("/api/merchant/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void buyerCanBrowseCatalog() throws Exception {
        String token = login("user1", "123456");
        mockMvc.perform(get("/api/catalog/products?category=手机数码")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].merchantName").isNotEmpty());
    }
}
