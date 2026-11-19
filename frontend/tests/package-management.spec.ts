import { test, expect } from '@playwright/test';

const MOCK_JWT =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.' +
  Buffer.from(
    JSON.stringify({
      sub: 'admin',
      upn: 'admin',
      preferred_username: 'admin',
      groups: ['gnm-admin'],
      exp: Math.floor(Date.now() / 1000) + 3600
    })
  ).toString('base64url') +
  '.signature';

test.describe('Agentless Package, Patch & Vulnerability Management UI', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((token) => {
      window.localStorage.setItem('gnm_auth_token', token);
    }, MOCK_JWT);

    await page.route('**/api/vault/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ initialized: true, unsealed: true })
      });
    });

    await page.route('**/api/vulnerabilities**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'vuln-1',
            deviceId: 'dev-1',
            deviceName: 'debian-edge-01',
            packageName: 'openssl',
            installedVersion: '3.0.11-1~deb12u2',
            fixedVersion: '3.0.14-1~deb12u1',
            fixAvailableNow: true,
            advisoryId: 'DEBIAN-CVE-2024-5535',
            cveAliases: ['CVE-2024-5535'],
            severity: 'CRITICAL',
            cvssScore: 9.1,
            summary: 'SSL_select_next_proto buffer overread',
            references: ['https://security-tracker.debian.org/tracker/CVE-2024-5535'],
            status: 'OPEN',
            suppressionNote: null,
            suppressedBy: null,
            firstDetectedAt: new Date().toISOString(),
            lastDetectedAt: new Date().toISOString(),
            resolvedAt: null
          }
        ])
      });
    });

    await page.route('**/api/settings**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{ key: 'gnm.listen.interface', value: 'eth0' }])
      });
    });

    await page.route('**/api/os-profiles/worker-health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          reachable: true,
          workerHealth: {
            status: 'UP',
            contractVersion: 'v1',
            workerVersion: '0.1.0',
            implementation: 'go',
            supportedParsers: ['DELIMITED', 'KV_BLOCKS', 'JSON_MAP', 'REGEX']
          },
          errorMessage: null
        })
      });
    });

    await page.route('**/api/os-profiles', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'prof-deb',
            slug: 'debian-apt',
            displayName: 'Debian / Ubuntu (APT)',
            transportType: 'SSH',
            priority: 10,
            enabled: true,
            isSystem: true,
            detectionRules: {},
            environmentVars: { LC_ALL: 'C' },
            inventorySpec: {},
            updateCheckSpec: {},
            patchExecSpec: {},
            vulnerabilitySpec: {},
            privilegeSpec: {},
            lockSpec: {}
          }
        ])
      });
    });
  });

  test('renders Fleet Vulnerability Center and displays CVE correlation', async ({ page }) => {
    await page.goto('/vulnerabilities');
    await expect(page.getByText('Fleet Vulnerability Center')).toBeVisible();
    await expect(page.getByText('DEBIAN-CVE-2024-5535')).toBeVisible();
    await expect(page.getByText('openssl')).toBeVisible();
  });

  test('renders OS Profiles Settings tab with worker health and built-in profiles', async ({
    page
  }) => {
    await page.goto('/settings');
    await page.getByRole('button', { name: /OS Profiles/i }).click();
    await expect(
      page.getByText(/Declarative OS & Package Manager Profiles/i)
    ).toBeVisible();
    await expect(page.getByText('debian-apt')).toBeVisible();
    await expect(page.getByText(/Worker Online/i)).toBeVisible();
  });
});

