import { expect, test } from '@playwright/test';

async function login(page: import('@playwright/test').Page, username: string, password: string) {
  await page.goto('/');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: '登 录' }).click();
}

test('买家可登录并访问秒杀、商品详情和客服退款入口', async ({ page }) => {
  await login(page, 'user1', '123456');
  await expect(page).toHaveURL(/\/user\/seckill/);
  await expect(page.getByText(/立即抢购|已售罄|您已购买过|未开抢/).first()).toBeVisible();

  await page.goto('/user/home');
  await page.locator('a[href*="/user/product/"]').first().click();
  await expect(page).toHaveURL(/\/user\/product\/\d+/);
  await expect(page.getByRole('button', { name: '加入购物车' })).toBeVisible();

  await page.goto('/user/support');
  await expect(page.getByText(/平台智能客服|专属客服/).first()).toBeVisible();
  await expect(page.getByPlaceholder('如：怎么申请退货退款？')).toBeVisible();
});

test('商家可进入Agent质量评测页面', async ({ page }) => {
  await login(page, 'huawei', '123456');
  await expect(page).toHaveURL(/\/merchant\/dashboard/);
  await page.goto('/merchant/evaluation');
  await expect(page.getByRole('heading', { name: 'Agent 评测与成本' })).toBeVisible();
  await expect(page.getByRole('button', { name: '运行 RAG + Agent 任务评测' })).toBeVisible();
});
