# Development checks

Build with Java 25:

```text
./mvnw verify
```

On Windows use `.\mvnw.cmd verify`. The build runs the unit suite and packages SQLite with the plugin. Paper, Vault, and the server logging API are provided by the server rather than bundled.

## Isolated server checks

`scripts/check-server.ps1` creates a new server under `target`, copies the supplied runtime dependencies and plugin JARs, and uses a separate localhost port. It does not copy worlds or edit the source server. Supply a Paper 26.3 JAR, VaultUnlocked, JustJobs, a Maven dependency cache, a compatible runtime folder, and Java 25:

```powershell
.\scripts\check-server.ps1 -PaperJar <paper.jar> -VaultJar <VaultUnlocked.jar> -JobsJar <JustJobs.jar> -DependencyCache <maven-repository> -PaperRuntime <server-folder> -JavaHome <jdk-folder>
```

The check plugin drives API-level player fixtures on a real Paper server. It checks provider startup, command registration, namespaces, aliases, costs, timer cancellation, permissions, teleport reservations, JustJobs payouts, Essentials import, reload rejection, restart recovery, and competing provider handling. Test phases shut down their isolated server automatically. Logs remain under the generated server folder.

Player fixtures do not replace an in-game playtest. Check actual client messages, command suggestions, player-to-player payments at range boundaries, teleport requests, and interactions with the live server's other plugins before deployment. Compatibility with every plugin or command wrapper is not implied.

## Installing a test build

```powershell
.\scripts\install-test.ps1 -ServerDirectory <server-folder>
```

This copies the built JAR, checks its SHA-256 hash, and removes the previous version after a successful copy. If the running JAR is locked, it stages a replacement in Paper's configured update folder instead. It does not start or restart the server.
