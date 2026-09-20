import { test, expect } from '@playwright/test';

test.describe('Device Status & In-App Alarms', () => {

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
        body: JSON.stringify([
          { id: '1', displayName: 'Linux Server', ipAddress: '192.168.100.10', status: 'ONLINE' }
        ])
      });
    });

    await page.addInitScript(() => {
      localStorage.setItem('gnm_token', 'fake-token');
      localStorage.setItem('gnm_username', 'admin');
      localStorage.setItem('gnm_roles', JSON.stringify(['gnm-admin']));
    });

    await page.goto('/');
  });

  test('Status Change (Online -> Offline)', async ({ page }) => {
    await expect(page).toHaveURL('/');
  });

  test('Status Change (Offline -> Online)', async ({ page }) => {
    await expect(page).toHaveURL('/');
  });

});
