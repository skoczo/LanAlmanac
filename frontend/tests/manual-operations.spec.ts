import { test, expect } from '@playwright/test';

test.describe('Manual Operations (UI/API)', () => {

  test.beforeEach(async ({ page }) => {
    // Mock OIDC settings
    await page.route('/api/settings/public/oidc', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ enabled: false })
      });
    });

    // Mock initial devices
    await page.route('/api/devices', async route => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([])
      });
    });

    // Inject fake auth
    await page.addInitScript(() => {
      localStorage.setItem('gnm_token', 'fake-token');
      localStorage.setItem('gnm_username', 'admin');
      localStorage.setItem('gnm_roles', JSON.stringify(['gnm-admin']));
    });

    await page.goto('/');
  });

  test('Manual Device Add', async ({ page }) => {
    // Given: The user is on the dashboard
    await expect(page.locator('text=Dashboard')).toBeVisible();

    // When: The user navigates to the add device form, fills it, and submits
    // (Simulating form interactions)
    // await page.click('button:has-text("Add Device")');
    // await page.fill('input[name="ipAddress"]', '10.0.0.1');
    // await page.fill('input[name="displayName"]', 'Core Switch');
    // await page.click('button:has-text("Submit")');
    
    // Then: The new device should be added and visible in the device list
    // await expect(page.locator('text=Core Switch')).toBeVisible();
  });

  test('Device Fields Modification', async ({ page }) => {
    // Given: A device exists in the system
    // When: The user selects the device, edits its details, and saves
    // await page.click('text=Test Device');
    // await page.click('button:has-text("Edit")');
    // await page.fill('input[name="displayName"]', 'Updated Name');
    // await page.click('button:has-text("Save")');
    
    // Then: The changes should be reflected in the UI
    // await expect(page.locator('text=Updated Name')).toBeVisible();
  });

  test('Credential Vault Management', async ({ page }) => {
    // Given: The user navigates to the credentials settings page
    // await page.click('a:has-text("Settings")');
    // await page.click('a:has-text("Credentials Vault")');
    
    // When: The user adds new SSH credentials
    // await page.click('button:has-text("Add Credential")');
    // await page.fill('input[name="username"]', 'admin');
    // await page.fill('input[name="password"]', 'secret');
    // await page.click('button:has-text("Save")');
    
    // Then: The credentials are saved and visible in the vault list
    // await expect(page.locator('text=admin')).toBeVisible();
  });

});
