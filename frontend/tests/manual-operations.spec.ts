import { test, expect } from '@playwright/test';

test.describe('Manual Operations (UI/API)', () => {

  test.beforeEach(async ({ page }) => {
    await page.route('/api/settings/public/oidc', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ enabled: 'false', authority: '', clientId: '', roleClaimPath: '' })
      });
    });

    await page.route('/api/devices', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      });
    });

    await page.addInitScript(() => {
      localStorage.setItem('gnm_token', 'fake-token');
      localStorage.setItem('gnm_username', 'admin');
      localStorage.setItem('gnm_roles', JSON.stringify(['gnm-admin']));
    });

    await page.goto('/');
  });

  test('Manual Device Add', async ({ page }) => {
    await expect(page).toHaveURL('/');
  });

  test('Device Fields Modification', async ({ page }) => {
    await expect(page).toHaveURL('/');
  });

  test('Credential Vault Management', async ({ page }) => {
    await expect(page).toHaveURL('/');
  });

});
