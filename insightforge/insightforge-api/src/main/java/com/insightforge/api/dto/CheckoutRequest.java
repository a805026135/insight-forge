package com.insightforge.api.dto;

import java.util.List;

/** 购物车结算请求：收货地址 + 选中的购物车条目。 */
public record CheckoutRequest(Long addressId, List<Long> cartItemIds) {
}
