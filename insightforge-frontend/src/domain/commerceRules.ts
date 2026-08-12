/** 前端业务展示规则。后端仍是最终权限、幂等和状态机边界。 */
export interface OrderPurchaseRef {
  activityId?: number | null;
  activitySkuId?: number | null;
}

export interface RefundDraftRef { orderId: string }

export function collectPurchasedSkuIds(orders: OrderPurchaseRef[], activityId: number): Set<number> {
  return new Set(orders
    .filter((order) => order.activityId === activityId && order.activitySkuId != null)
    .map((order) => Number(order.activitySkuId)));
}

export function seckillButtonLabel(input: {
  purchased: boolean; loading: boolean; stock: number; live: boolean;
}): string {
  if (input.purchased) return '您已购买过';
  if (input.loading) return '抢购中...';
  if (input.stock <= 0) return '已售罄';
  return input.live ? '立即抢购' : '未开抢';
}

export function isSeckillDisabled(input: {
  purchased: boolean; loading: boolean; stock: number; live: boolean;
}): boolean {
  return input.purchased || input.loading || input.stock <= 0 || !input.live;
}

export function discountPercent(marketPrice?: number | null, salePrice?: number | null): number {
  if (marketPrice == null || salePrice == null || marketPrice <= 0 || salePrice >= marketPrice) return 0;
  return Math.round((1 - salePrice / marketPrice) * 100);
}

/** 真实退款提交成功后移除对应草稿，防止用户重复点击。 */
export function removeSubmittedRefundDraft<T extends RefundDraftRef>(drafts: T[] | undefined, orderId: string): T[] {
  return (drafts ?? []).filter((draft) => draft.orderId !== orderId);
}

/** HTTP 200 但业务 code 非 0 仍是失败，不能展示成退款成功。 */
export function unwrapApiResult<T>(result: { code: number; message?: string; data: T }): T {
  if (result.code !== 0) throw new Error(result.message || '操作失败');
  return result.data;
}
