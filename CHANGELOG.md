# Changelog

## 0.0.2 (Unreleased)

- Currency displays now default to exactly two decimal places while retaining four-decimal accounting.
- Added reloadable `economy.display-precision` for display rounding without changing saved balances or Vault transaction precision.

## 0.0.1 (2026-10-22)

- First prerelease for Paper 26.3 and Java 25, with a Vault economy provider and saved UUID balances.
- Added balance lookup, paginated balance rankings, and player payments with optional offline recipients and distance limits.
- Added server spawn and teleport requests with acceptance, denial, cancellation, expiry, and safe destination checks.
- Added configurable command costs and standstill timers, including subcommand rules, aliases, namespaces, and separate bypass permissions.
- Teleport fees settle only after completion. Failed teleports return reserved funds, and unfinished reservations recover on restart.
- Added administrative balance changes, checked database backups, and an Essentials balance importer with preview and repeat protection.
- Commands can be disabled individually to release their names and aliases. Settings and messages support validated reloads.
- Added tests for transfers, precision, storage failures, abrupt exits, imports, and Paper/VaultUnlocked/JustJobs integration.
