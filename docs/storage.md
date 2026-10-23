# Storage, backups, and Essentials migration

## Balances

Balances are keyed by UUID in `plugins/JustEconomy/balances.db`. The database uses SQLite WAL mode and full synchronous commits. Successful money operations are acknowledged after commit; player transfers update both accounts in one transaction. Monetary values are stored as integer units of the configured precision.

Vault's interface is synchronous. Its calls use a serialized database connection, so a durable write can briefly wait for disk I/O. Leaderboards use an index and fetch only the requested page. Essentials source scanning runs asynchronously. This release does not share balances across servers.

Database integrity failures stop startup rather than replacing saved data. A transaction-level storage error closes the connection to prevent further unconfirmed writes. Resolve the storage problem and restart; a configuration reload does not reopen the database. JustJobs retains unpaid earnings while its economy provider is unavailable.

## Teleport reservations

After the timer and final checks, a teleport fee is reserved in a saved transaction. Reserved funds are temporarily unavailable to spend. A completed teleport settles that reservation; a rejected or failed teleport returns it. Deposits and administrative changes respect the maximum balance including reserved funds, so a refund can fit.

On startup, unfinished reservations are refunded. If the process exits after a teleport completes but before its settlement is saved, the player can receive that teleport for free. The recovery policy favors returning an uncertain fee over retaining a fee for a failed teleport. The server cannot make a world teleport and a database commit one atomic operation.

## Backups

`/economy backup` saves a consistent copy under `plugins/JustEconomy/backups` and checks its integrity. The copy includes committed WAL data. Backups are also made before confirmed imports. Keep copies outside the server machine for recovery from disk failure.

To restore, stop the server, preserve the current database and its `-wal` and `-shm` companions, and replace the database with a checked backup. Do not mix old WAL files with a restored database. Restart only after the files are in place. Database restoration can also restore older import records and balances, so review any later economy activity before resuming play.

## Essentials import

The importer reads UUID-named YAML files from the sibling `Essentials/userdata` directory. It reads `money` and `last-account-name`, leaving the source untouched. No Essentials classes or running economy provider are required.

1. Back up the existing server and stop Essentials balance changes. Do not run two active economy providers.
2. Keep the Essentials data files present, and start JustEconomy with its intended precision and maximum balance.
3. Run `/economy import preview`. Review counts of eligible new accounts, existing accounts, and invalid records.
4. Run `/economy import confirm` within 60 seconds using the same sender. If the source changes, make a new preview.

The import **only creates absent accounts**. It never adds to or overwrites an existing JustEconomy account, including an account with a zero balance. Players should therefore be kept out until migration is complete. Negative, oversized, malformed, or excessively precise source balances are skipped and included in the invalid count. Files without a balance are ignored.

Each confirmed batch and its import records commit together. An interrupted batch rolls back. Repeat imports skip already imported or otherwise existing UUIDs, even if the source changes. The confirmation reports the number imported and skipped and the backup filename. Spawn, teleport settings, inventories, and other Essentials data are not imported.
