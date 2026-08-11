package com.insightforge.api.dto;

/**
 * 可联系商家（来自用户最近购买记录），用于"转商家客服"选择。
 */
public record MerchantContact(Long merchantId, String merchantName) {
}
