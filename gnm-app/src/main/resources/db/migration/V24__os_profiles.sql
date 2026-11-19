-- V24: Multi-category credentials and data-driven OS Profiles for Agentless Package Management

CREATE TABLE credential_category (
    credential_id UUID NOT NULL REFERENCES credential(id) ON DELETE CASCADE,
    category VARCHAR(64) NOT NULL,
    PRIMARY KEY (credential_id, category)
);

CREATE INDEX idx_credential_category_lookup ON credential_category(category);

CREATE TABLE os_profile (
    id                  UUID PRIMARY KEY,
    slug                VARCHAR(64) NOT NULL UNIQUE,
    display_name        VARCHAR(128) NOT NULL,
    transport_type      VARCHAR(32) NOT NULL DEFAULT 'SSH',
    priority            INT NOT NULL DEFAULT 100,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    is_system           BOOLEAN NOT NULL DEFAULT FALSE,
    detection_rules     JSONB NOT NULL,
    environment_vars    JSONB NOT NULL DEFAULT '{"LC_ALL":"C","LANG":"C"}'::jsonb,
    inventory_spec      JSONB NOT NULL,
    update_check_spec   JSONB NOT NULL,
    patch_exec_spec     JSONB NOT NULL,
    vulnerability_spec  JSONB NOT NULL,
    privilege_spec      JSONB NOT NULL,
    lock_spec           JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_os_profile_enabled_priority ON os_profile(enabled, priority ASC);

ALTER TABLE physical_device
    ADD COLUMN os_profile_id UUID REFERENCES os_profile(id) ON DELETE SET NULL,
    ADD COLUMN os_profile_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN os_facts JSONB,
    ADD COLUMN os_detected_at TIMESTAMPTZ;

-- Seed 3 built-in system profiles (is_system = TRUE)

INSERT INTO os_profile (
    id, slug, display_name, transport_type, priority, enabled, is_system,
    detection_rules, environment_vars, inventory_spec, update_check_spec,
    patch_exec_spec, vulnerability_spec, privilege_spec, lock_spec
) VALUES
(
    '10000000-0000-4000-8000-000000000001',
    'debian-apt',
    'Debian / Ubuntu Family (APT)',
    'SSH',
    10,
    TRUE,
    TRUE,
    '{
      "matchMode": "ALL",
      "conditions": [
        {"fact": "kernel", "op": "EQUALS", "value": "Linux"},
        {"fact": "os.id_or_like", "op": "CONTAINS_TOKEN", "value": "debian"}
      ],
      "verifyCommand": "command -v dpkg-query >/dev/null 2>&1"
    }'::jsonb,
    '{"LC_ALL": "C", "LANG": "C"}'::jsonb,
    '{
      "command": "dpkg-query -W -f=''${Status}\t${Package}\t${Version}\t${Architecture}\t${source:Package}\t${source:Version}\n''",
      "requiresPrivilege": false,
      "timeoutSeconds": 60,
      "parserType": "DELIMITED",
      "parserConfig": {
        "delimiter": "\t",
        "filterColumn": 0,
        "filterEquals": "install ok installed",
        "columns": {
          "name": 1,
          "version": 2,
          "arch": 3,
          "sourceName": 4,
          "sourceVersion": 5
        }
      },
      "purlTemplate": "pkg:deb/{{os.id}}/{{name}}@{{version}}?arch={{arch}}"
    }'::jsonb,
    '{
      "refreshCommand": "apt-get update -qq",
      "refreshRequiresPrivilege": true,
      "checkCommand": "apt-get -s -o Debug::NoLocking=true upgrade",
      "checkRequiresPrivilege": false,
      "noUpdatesExitCodes": [0],
      "updatesAvailableExitCodes": [0],
      "timeoutSeconds": 120,
      "parserType": "REGEX_LINE",
      "parserConfig": {
        "pattern": "^Inst\\s+(?<packageName>\\S+)\\s+\\[(?<currentVersion>[^\\]]+)\\]\\s+\\((?<candidateVersion>\\S+)\\s+([^\\)]*)\\)"
      }
    }'::jsonb,
    '{
      "preCheckCommand": null,
      "refreshCommand": "apt-get update -qq",
      "fullUpgradeCommand": "apt-get -y -o DPkg::Lock::Timeout=60 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade",
      "securityUpgradeCommand": null,
      "selectedUpgradeTemplate": "apt-get -y -o DPkg::Lock::Timeout=60 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install --only-upgrade -- {{packages}}",
      "rebootCheckCommand": "test -f /var/run/reboot-required",
      "rebootRequiredExitCodes": [0],
      "requiresPrivilege": true,
      "nonInteractiveEnv": {
        "DEBIAN_FRONTEND": "noninteractive",
        "NEEDRESTART_MODE": "a",
        "LC_ALL": "C"
      },
      "timeoutSeconds": 1800,
      "idleTimeoutSeconds": 300
    }'::jsonb,
    '{
      "enabled": true,
      "osvEcosystemTemplate": "Debian:{{os.version_major}}",
      "queryField": "SOURCE_PACKAGE"
    }'::jsonb,
    '{
      "elevationMode": "SUDO_NOPASSWD",
      "wrapperTemplate": "sudo -n {{command}}",
      "sudoersAllowedCommands": [
        "/usr/bin/apt-get update -qq",
        "/usr/bin/apt-get -y -o DPkg::Lock::Timeout=60 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade",
        "/usr/bin/apt-get -y -o DPkg::Lock::Timeout=60 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install --only-upgrade -- *"
      ]
    }'::jsonb,
    '{
      "retryableExitCodes": [100],
      "lockErrorPattern": "(Could not get lock|Unable to acquire the dpkg frontend lock|Waiting for process with pid)",
      "maxRetries": 3,
      "backoffSeconds": [15, 30, 60]
    }'::jsonb
),
(
    '10000000-0000-4000-8000-000000000002',
    'rhel-dnf',
    'RHEL / Rocky / AlmaLinux / Fedora (RPM + DNF)',
    'SSH',
    20,
    TRUE,
    TRUE,
    '{
      "matchMode": "ALL",
      "conditions": [
        {"fact": "kernel", "op": "EQUALS", "value": "Linux"},
        {"fact": "os.id_or_like", "op": "IN", "value": "rhel,rocky,almalinux,centos,fedora"}
      ],
      "verifyCommand": "command -v rpm >/dev/null 2>&1"
    }'::jsonb,
    '{"LC_ALL": "C", "LANG": "C"}'::jsonb,
    '{
      "command": "rpm -qa --qf ''%{NAME}\t%{EPOCHNUM}:%{VERSION}-%{RELEASE}\t%{ARCH}\t%{SOURCERPM}\n''",
      "requiresPrivilege": false,
      "timeoutSeconds": 60,
      "parserType": "DELIMITED",
      "parserConfig": {
        "delimiter": "\t",
        "stripLeadingZeroEpoch": true,
        "columns": {
          "name": 0,
          "version": 1,
          "arch": 2,
          "sourceName": 3
        }
      },
      "purlTemplate": "pkg:rpm/{{os.id}}/{{name}}@{{version}}?arch={{arch}}"
    }'::jsonb,
    '{
      "refreshCommand": null,
      "refreshRequiresPrivilege": false,
      "checkCommand": "dnf -q check-update",
      "checkRequiresPrivilege": false,
      "noUpdatesExitCodes": [0],
      "updatesAvailableExitCodes": [0, 100],
      "timeoutSeconds": 120,
      "parserType": "REGEX_LINE",
      "parserConfig": {
        "pattern": "^(?<packageName>[A-Za-z0-9._+-]+)\\.(?<arch>x86_64|aarch64|noarch|i686|armv7hl)\\s+(?<candidateVersion>\\S+)\\s+\\S+$"
      }
    }'::jsonb,
    '{
      "preCheckCommand": null,
      "refreshCommand": null,
      "fullUpgradeCommand": "dnf -y upgrade",
      "securityUpgradeCommand": "dnf -y upgrade --security",
      "selectedUpgradeTemplate": "dnf -y upgrade -- {{packages}}",
      "rebootCheckCommand": "needs-restarting -r >/dev/null 2>&1 && exit 1 || exit 0",
      "rebootRequiredExitCodes": [0],
      "requiresPrivilege": true,
      "nonInteractiveEnv": {
        "LC_ALL": "C"
      },
      "timeoutSeconds": 1800,
      "idleTimeoutSeconds": 300
    }'::jsonb,
    '{
      "enabled": true,
      "osvEcosystemTemplate": "Rocky Linux:{{os.version_major}}",
      "queryField": "BINARY_PACKAGE"
    }'::jsonb,
    '{
      "elevationMode": "SUDO_NOPASSWD",
      "wrapperTemplate": "sudo -n {{command}}",
      "sudoersAllowedCommands": [
        "/usr/bin/dnf -y upgrade",
        "/usr/bin/dnf -y upgrade --security",
        "/usr/bin/dnf -y upgrade -- *"
      ]
    }'::jsonb,
    '{
      "retryableExitCodes": [1],
      "lockErrorPattern": "(Waiting for process with pid|Failed to obtain lock)",
      "maxRetries": 3,
      "backoffSeconds": [15, 30, 60]
    }'::jsonb
),
(
    '10000000-0000-4000-8000-000000000003',
    'alpine-apk',
    'Alpine Linux (APK)',
    'SSH',
    30,
    TRUE,
    TRUE,
    '{
      "matchMode": "ALL",
      "conditions": [
        {"fact": "kernel", "op": "EQUALS", "value": "Linux"},
        {"fact": "os.id_or_like", "op": "CONTAINS_TOKEN", "value": "alpine"}
      ],
      "verifyCommand": "test -f /lib/apk/db/installed"
    }'::jsonb,
    '{"LC_ALL": "C", "LANG": "C"}'::jsonb,
    '{
      "command": "cat /lib/apk/db/installed",
      "requiresPrivilege": false,
      "timeoutSeconds": 60,
      "parserType": "KEY_VALUE_BLOCKS",
      "parserConfig": {
        "keyValueSeparator": ":",
        "fields": {
          "name": "P",
          "version": "V",
          "arch": "A",
          "sourceName": "o"
        }
      },
      "purlTemplate": "pkg:apk/alpine/{{name}}@{{version}}?arch={{arch}}"
    }'::jsonb,
    '{
      "refreshCommand": "apk update -q",
      "refreshRequiresPrivilege": true,
      "checkCommand": "apk version -l ''<''",
      "checkRequiresPrivilege": false,
      "noUpdatesExitCodes": [0],
      "updatesAvailableExitCodes": [0],
      "timeoutSeconds": 120,
      "parserType": "REGEX_LINE",
      "parserConfig": {
        "pattern": "^(?<packageName>\\S+)-(?<currentVersion>[0-9][^\\s]*)\\s+<\\s+(?<candidateVersion>\\S+)$"
      }
    }'::jsonb,
    '{
      "preCheckCommand": null,
      "refreshCommand": "apk update -q",
      "fullUpgradeCommand": "apk upgrade --no-interactive",
      "securityUpgradeCommand": null,
      "selectedUpgradeTemplate": "apk add --upgrade --no-interactive -- {{packages}}",
      "rebootCheckCommand": "false",
      "rebootRequiredExitCodes": [0],
      "requiresPrivilege": true,
      "nonInteractiveEnv": {
        "LC_ALL": "C"
      },
      "timeoutSeconds": 900,
      "idleTimeoutSeconds": 180
    }'::jsonb,
    '{
      "enabled": true,
      "osvEcosystemTemplate": "Alpine:v{{os.version_major_minor}}",
      "queryField": "BINARY_PACKAGE"
    }'::jsonb,
    '{
      "elevationMode": "SUDO_NOPASSWD",
      "wrapperTemplate": "sudo -n {{command}}",
      "sudoersAllowedCommands": [
        "/sbin/apk update -q",
        "/sbin/apk upgrade --no-interactive",
        "/sbin/apk add --upgrade --no-interactive -- *"
      ]
    }'::jsonb,
    '{
      "retryableExitCodes": [1],
      "lockErrorPattern": "(Unable to lock database)",
      "maxRetries": 3,
      "backoffSeconds": [10, 20, 40]
    }'::jsonb
);

