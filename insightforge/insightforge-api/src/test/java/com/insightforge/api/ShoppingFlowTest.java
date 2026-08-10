package com.insightforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 17 购物闭环：地址 → 加购 → 结算 → 订单中心（含商品名/商家名/收货快照）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class ShoppingFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;

    private String token;

    private String login() throws Exception {
        var res = mockMvc.perform(post("/api/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"user1\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }

    private JsonNode postJson(String path, String body, String bearer) throws Exception {
        var res = mockMvc.perform(post(path).contentType(APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + bearer))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void fullShoppingFlow() throws Exception {
        token = login();

        // 自建专用商品 + 活动SKU，避免与其他秒杀测试共享种子库存
        Product p = new Product();
        p.setName("测试专用商品");
        p.setSku("SKU-TEST-FLOW");
        p.setCostPrice(new BigDecimal("100"));
        p.setMarketPrice(new BigDecimal("200"));
        p.setMerchantId(1L);
        p.setCategory("测试分类");
        p.setStatus("ON_SALE");
        productMapper.insert(p);
        ActivitySku sku = new ActivitySku();
        sku.setActivityId(1L);
        sku.setSkuId(p.getId());
        sku.setSeckillPrice(new BigDecimal("199"));
        sku.setTotalStock(100);
        sku.setStock(100);
        sku.setSold(0);
        sku.setVersion(0);
        sku.setMerchantId(1L);
        activitySkuMapper.insert(sku);

        // 1) 创建地址
        JsonNode addr = postJson("/api/user/addresses",
                "{\"receiver\":\"测试小明\",\"phone\":\"13800001111\",\"province\":\"广东省\",\"city\":\"深圳市\",\"district\":\"南山区\",\"detail\":\"科技园 1 号\",\"isDefault\":true}",
                token);
        org.junit.jupiter.api.Assertions.assertEquals(0, addr.path("code").asInt(), addr.toString());
        long addressId = addr.path("data").path("id").asLong();

        // 2) 加购 iPhone（活动SKU id=1）
        JsonNode add = postJson("/api/user/cart",
                "{\"productId\":" + p.getId() + ",\"qty\":1}", token);
        org.junit.jupiter.api.Assertions.assertEquals(0, add.path("code").asInt(), add.toString());

        // 3) 购物车列表
        var cartRes = mockMvc.perform(get("/api/user/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].productName").isNotEmpty())
                .andExpect(jsonPath("$.data[0].merchantName").isNotEmpty())
                .andReturn();
        JsonNode cart = objectMapper.readTree(cartRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        long cartId = cart.get(0).path("id").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(cart.size() >= 1);

        // 4) 结算
        JsonNode checkout = postJson("/api/user/checkout",
                "{\"addressId\":" + addressId + ",\"cartItemIds\":[" + cartId + "]}", token);
        org.junit.jupiter.api.Assertions.assertEquals(0, checkout.path("code").asInt(), checkout.toString());
        String orderId = checkout.path("data").asText();

        // 5) 订单中心：订单含商品名/商家名/收货快照
        var ordersRes = mockMvc.perform(get("/api/user/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode orders = objectMapper.readTree(ordersRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        org.junit.jupiter.api.Assertions.assertTrue(orders.size() >= 1);
        JsonNode order = null;
        for (JsonNode o : orders) {
            if (orderId.equals(o.path("orderId").asText())) {
                order = o;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(order);
        org.junit.jupiter.api.Assertions.assertEquals("测试小明", order.path("receiver").asText());
        org.junit.jupiter.api.Assertions.assertTrue(order.path("items").size() >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(
                order.path("items").get(0).path("merchantName").asText().contains("Apple"));

        // 6) 支付 → 确认收货（含 SHIPPED 状态机过渡）
        JsonNode pay = postJson("/api/user/orders/" + orderId + "/pay", "{}", token);
        org.junit.jupiter.api.Assertions.assertEquals(0, pay.path("code").asInt(), pay.toString());
        JsonNode confirm = postJson("/api/user/orders/" + orderId + "/confirm", "{}", token);
        org.junit.jupiter.api.Assertions.assertEquals(0, confirm.path("code").asInt(), confirm.toString());

        // 7) 购物车应已清空该条目
        var cartAfter = mockMvc.perform(get("/api/user/cart").header("Authorization", "Bearer " + token))
                .andReturn();
        JsonNode cart2 = objectMapper.readTree(cartAfter.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        for (JsonNode c : cart2) {
            org.junit.jupiter.api.Assertions.assertNotEquals(cartId, c.path("id").asLong());
        }
    }
}
