# JustEconomy

A Paper economy provider with payments, spawn, teleport requests, and configurable command costs and standstill timers.

JustEconomy stores one balance per player across all worlds and exposes it through Vault. It works independently of [JustJobs](https://github.com/RokeyDoess/JustJobs), or alongside it as the provider for job earnings.

## Install

Requires **Paper 26.3, Java 25, and Vault or VaultUnlocked**. Essentials is optional. Folia is not supported.

Download the JAR from [Releases](https://github.com/RokeyDoess/JustEconomy/releases), place it in `plugins`, and start the server. Configuration and a local SQLite database are created in `plugins/JustEconomy`.

Use one active economy provider. Disable the old provider before switching to JustEconomy. If keeping Essentials for other features, disable its economy and conflicting commands using the settings supported by that Essentials version. JustEconomy refuses to start with another registered economy provider and disables itself if a competing provider takes over later.

**0.0.1 is a prerelease.** Back up existing economy data before migrating and test configuration changes before using them on a live server.

Development on `main` targets the unreleased `0.0.2`.

## Commands and features

- `/balance`, `/balancetop`, and `/pay`, including optional distance limits and offline payments.
- `/spawn`, `/setspawn`, and teleport requests with acceptance, denial, cancellation, and expiry.
- Costs and standstill timers for configured vanilla or plugin commands, including subcommands.
- Separate permissions for bypassing fees and timers. Operators do not bypass either by default.
- Administrative balance changes, database backups, and an Essentials balance importer with preview and confirmation.

Every command can be disabled independently. Disabled commands and aliases are left available for another plugin. Registration changes require a restart; other settings and messages can be reloaded.

External command fees apply after the timer, immediately before dispatch. The target plugin may still reject the command. Built-in teleport fees apply only to completed teleports. Movement and damage cancel timers by default; looking around is allowed.

[Commands and permissions](docs/commands.md) · [Configuration](docs/configuration.md) · [Storage and migration](docs/storage.md) · [Changelog](CHANGELOG.md)

## Build and checks

Use Java 25 and run `./mvnw verify` (`.\mvnw.cmd verify` on Windows). The current development build is `target/JustEconomy-0.0.2-SNAPSHOT.jar`.

The test suite covers monetary precision, concurrent transfers, rollback, backups, storage failures, imports, and configuration validation. [Development checks](docs/development.md) describes the isolated Paper/VaultUnlocked/JustJobs integration suite and its limits.

## License

[GNU General Public License v3.0](LICENSE).
