export interface OsProfileSummary {
  id: string
  slug: string
  displayName: string
  transportType: string
  priority: number
  enabled: boolean
  isSystem: boolean
}

export interface DeviceOsProfileStatus {
  osProfileId: string | null
  osProfileSlug: string | null
  osProfileName: string | null
  pinned: boolean
  osFacts: Record<string, string>
  osDetectedAt: string | null
  effectiveCredentialUsername: string | null
  effectiveCredentialSource: string
  sshHostKeyTrusted: boolean
}

export interface DevicePackageItem {
  id: string
  name: string
  version: string
  arch: string
  sourceName: string
  sourceVersion: string
  ecosystem: string
  purl: string
  lastSeenAt: string
  openVulnerabilityCount: number
  candidateVersion?: string | null
  isSecurityUpdate?: boolean
}

export interface DevicePackageScanResult {
  id: string
  deviceId: string
  osProfileSlug: string | null
  credentialUsername: string | null
  usedFallback: boolean
  status: string
  packageCount: number
  skippedLines: number
  errorCode: string | null
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface DeviceVulnerabilityItem {
  id: string
  deviceId: string
  deviceName: string | null
  packageName: string
  installedVersion: string
  fixedVersion: string | null
  fixAvailableNow: boolean
  advisoryId: string
  cveAliases: string[]
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN' | string
  cvssScore: number | null
  summary: string | null
  references: string[]
  status: 'OPEN' | 'FIXED' | 'ACCEPTED_RISK' | 'FALSE_POSITIVE' | string
  suppressionNote: string | null
  suppressedBy: string | null
  firstDetectedAt: string
  lastDetectedAt: string
  resolvedAt: string | null
}

export interface DeviceAvailableUpdateItem {
  id: string
  packageName: string
  arch: string
  currentVersion: string | null
  candidateVersion: string
  isSecurityUpdate: boolean
  fixedAdvisories: string[]
  checkedAt: string
}

export interface SudoersSnippet {
  username: string
  osProfileSlug: string
  osProfileName: string
  snippet: string
}

export interface PatchJobItem {
  id: string
  deviceId: string
  osProfileSlug: string | null
  credentialUsername: string | null
  usedFallback: boolean
  mode: 'FULL_UPGRADE' | 'SECURITY_ONLY' | 'SELECTED_PACKAGES' | string
  requestedPackages: string[]
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CONNECTION_LOST' | 'CANCELLED' | string
  errorCode: string | null
  errorMessage: string | null
  rebootRequired: boolean
  triggeredBy: string | null
  startedAt: string
  finishedAt: string | null
}

export interface PatchJobLogLine {
  seq: number
  stream: string
  line: string
  createdAt: string
}

export interface PatchJobDetail {
  job: PatchJobItem
  logs: PatchJobLogLine[]
}

export interface DevicePackageScheduleDto {
  deviceId: string
  inventoryEnabled: boolean
  inventoryCron: string
  nextInventoryRunAt: string | null
  lastInventoryRunAt: string | null
  updateCheckEnabled: boolean
  updateCheckCron: string
  nextUpdateCheckRunAt: string | null
  lastUpdateCheckRunAt: string | null
  autoPatchEnabled: boolean
  autoPatchMode: 'SECURITY_ONLY' | 'FULL_UPGRADE' | string
  autoPatchCron: string
  nextAutoPatchRunAt: string | null
  lastAutoPatchRunAt: string | null
  timezone: string
  updatedBy: string | null
  updatedAt: string | null
  nextPreviewRuns: string[]
}


