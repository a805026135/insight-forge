import { describe, expect, it } from 'vitest';
import {
  collectPurchasedSkuIds, discountPercent, isSeckillDisabled,
  removeSubmittedRefundDraft, seckillButtonLabel, unwrapApiResult,
} from './commerceRules';

describe('秒杀限购展示规则', () => {
  it('只收集当前活动且具有活动 SKU 的历史订单', () => {
    const ids = collectPurchasedSkuIds([
      { activityId: 1, activitySkuId: 11 }, { activityId: 2, activitySkuId: 22 },
      { activityId: 1, activitySkuId: null }, { activityId: 1, activitySkuId: 12 },
    ], 1);
    expect([...ids]).toEqual([11, 12]);
  });

  it('已购买状态优先于加载、售罄和活动状态', () => {
    const state = { purchased: true, loading: true, stock: 0, live: false };
    expect(seckillButtonLabel(state)).toBe('您已购买过');
    expect(isSeckillDisabled(state)).toBe(true);
  });

  it.each([
    [{ purchased: false, loading: true, stock: 1, live: true }, '抢购中...'],
    [{ purchased: false, loading: false, stock: 0, live: true }, '已售罄'],
    [{ purchased: false, loading: false, stock: 1, live: false }, '未开抢'],
    [{ purchased: false, loading: false, stock: 1, live: true }, '立即抢购'],
  ] as const)('根据状态显示正确按钮文案', (state, label) => {
    expect(seckillButtonLabel(state)).toBe(label);
  });
});

describe('商品与退款业务规则', () => {
  it('折扣仅在市场价有效且高于售价时计算', () => {
    expect(discountPercent(100, 80)).toBe(20);
    expect(discountPercent(80, 100)).toBe(0);
    expect(discountPercent(null, 80)).toBe(0);
  });

  it('退款成功后只移除对应订单草稿', () => {
    expect(removeSubmittedRefundDraft([
      { orderId: 'A', reason: '破损' }, { orderId: 'B', reason: '不需要' },
    ], 'A')).toEqual([{ orderId: 'B', reason: '不需要' }]);
  });

  it('不能把 HTTP 成功但业务失败误判成退款成功', () => {
    expect(() => unwrapApiResult({ code: 409, message: '已有售后单', data: null })).toThrow('已有售后单');
    expect(unwrapApiResult({ code: 0, data: { id: 7 } })).toEqual({ id: 7 });
  });
});
