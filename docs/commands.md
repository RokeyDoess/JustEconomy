# Commands and permissions

All commands also have a `justeconomy:` form, such as `/justeconomy:balance`. If another plugin owns an ordinary command name, JustEconomy keeps the namespaced version and logs the conflict. Disable the competing command or disable JustEconomy's version as appropriate.

| Command | Aliases | Permission | Default |
| --- | --- | --- | --- |
| `/balance [player]` | `/bal`, `/money` | `justeconomy.balance` | Everyone |
| `/balancetop [page]` | `/baltop` | `justeconomy.balancetop` | Everyone |
| `/pay <player> <amount>` | — | `justeconomy.pay` | Everyone |
| `/spawn` | — | `justeconomy.spawn` | Everyone |
| `/setspawn` | — | `justeconomy.setspawn` | Operators |
| `/tpa <player>` | — | `justeconomy.tpa` | Everyone |
| `/tpaccept [player]` | — | `justeconomy.tpaccept` | Everyone |
| `/tpdeny [player]` | — | `justeconomy.tpdeny` | Everyone |
| `/tpacancel` | — | `justeconomy.tpacancel` | Everyone |
| `/economy` | — | `justeconomy.economy` | Operators |

Viewing another player's balance also requires `justeconomy.balance.others`, granted to operators by default. Console can view named balances and leaderboards and use administrative commands.

`justeconomy.bypass.cost` and `justeconomy.bypass.timer` independently bypass command fees and standstill timers. Both default to false, including for operators. They do not bypass permissions, payment range, insufficient transfer funds, or teleport safety checks.

## Payments

Players must have a known account. Names are resolved from online players, saved accounts, and Paper's local player cache; commands do not perform online name lookups. Accounts use UUIDs, so a name change does not reset a balance. Ambiguous saved names are not resolved to an arbitrary account.

Payments default to online recipients. If a range is configured, both players must be online and in the same world. The range includes height and allows the exact boundary. Self-payments, zero payments, negative amounts, and amounts exceeding the supported precision are rejected.

The leaderboard includes saved offline accounts, ordered by balance and then UUID for ties. Pages default to ten players.

## Teleports

`/setspawn` saves one server-wide destination. `/spawn` uses it without changing the world's natural spawn or respawn rules.

`/tpa` asks to move the requester to the recipient. Each player can have one outgoing request. Requests expire after 60 seconds by default. If several players have requested a teleport, specify the player when accepting or denying. `/tpacancel` withdraws the outgoing request or cancels that player's pending warmup. Disconnects clear related requests.

For an accepted request, the requester waits through the configured timer and pays the fee. The recipient pays no teleport fee. A separate explicit rule on `/tpaccept` can still charge for using that command.

Destinations are loaded asynchronously and checked for a solid floor, clear feet/head space, world-border limits, and common hazards. Unsafe destinations are rejected rather than searching for another spot. A player standing in midair can therefore be an invalid target. Teleport events remain cancellable by other plugins. See [storage](storage.md) for how fee reservations recover after interruptions.

## Administration

| Command | Effect |
| --- | --- |
| `/economy give <player> <amount>` | Add to a known player's balance. |
| `/economy take <player> <amount>` | Remove funds without allowing a negative balance. |
| `/economy set <player> <amount>` | Set an available balance, including zero. |
| `/economy reload` | Validate and apply settings and messages; cancel pending warmups and requests. |
| `/economy backup` | Save and integrity-check a database backup. |
| `/economy import preview` | Preview Essentials balances without changing accounts. |
| `/economy import confirm` | Import the previewed source after confirmation, with a backup. |

Reloads are rejected while a teleport is completing. Command registration changes take effect only after a restart. A rejected reload leaves working settings active.
