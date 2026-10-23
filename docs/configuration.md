# Configuration

Settings are created in `plugins/JustEconomy` on the first start. Existing files are preserved. Missing ordinary settings use their defaults; invalid values reject startup or reload with an error.

| File | Contents |
| --- | --- |
| `config.yml` | Currency, command enablement, payment range, leaderboards, and teleport defaults. |
| `command-rules.yml` | Costs and timers for selected commands. |
| `messages.yml` | Player-facing messages, using `&` color codes and named placeholders. An empty message disables that message. |
| `spawn.yml` | Destination written by `/setspawn`. |

## Currency

The default currency symbol is `$`. Precision defaults to four decimal places so small rewards such as `0.0085` can be retained. Display uses two places and shows additional places when needed. Currency names, the starting balance, and maximum balance are configurable.

Precision is stored with the database and cannot be changed by reload or restart. Changing it requires a deliberate data conversion outside this release. Reducing the maximum below an existing balance, including reserved fees, is rejected. Internal amounts are bounded to prevent overflow. Vault's double-valued amounts are rounded half up to the configured precision; positive amounts that round to zero are rejected. Player-entered amounts must already fit the precision.

## Payments and timers

`payments.allow-offline` defaults to false. `payments.range: 0` disables range restrictions. A positive range measures the straight-line three-dimensional distance in blocks; it also requires both players online and in the same world, regardless of the offline setting.

Teleport defaults are under `teleport.spawn` and `teleport.tpa`. Each has `cost` and `standstill-seconds`. Both default to a free teleport after three seconds. A matching command rule replaces both values for that action.

`standstill.cancel-on-movement` and `standstill.cancel-on-damage` default to true. Movement is measured from the starting position with a default tolerance of 0.1 blocks; yaw and pitch changes do not count. Death, disconnects, intervening teleports, shutdown, and accepted reloads cancel pending warmups. A player can have one pending action at a time.

## Command rules

Replace `rules: []` with entries such as:

```yaml
rules:
  - command: home
    cost: '10'
    standstill-seconds: 0
  - command: minecraft:give
    cost: '0'
    standstill-seconds: 5
  - command: warp market
    cost: '2.50'
    standstill-seconds: 3
```

Commands and subcommands match literal words, ignoring case and repeated whitespace. A command-wide rule also matches arguments. The longest matching prefix wins; overlapping rules never stack. If equal-length rules resolve through aliases to the same command, the first rule wins. An entry can use `enabled: false` to disable that rule.

Plugin aliases and namespaced forms resolve through the command registry. Native aliases follow Paper's command redirects. Simple `commands.yml` aliases that forward once using `$1-` are expanded before matching. Complex aliases that run several commands or rewrite arguments must have a rule on the alias itself. Commands dispatched by GUIs, scripts, command blocks, console, or other plugins are outside generic player-input interception; built-in JustEconomy commands apply their own matching rules when called as a player.

For external commands, the timer completes first. Permissions and cancellation are checked again, funds are withdrawn, and the command is dispatched as the original player. A cancellation before dispatch costs nothing. If the target plugin then refuses the command, the fee remains. Examples include a missing home, invalid arguments, or a destination denied by that plugin. There is no universal success signal across plugins.

Built-in spawn and accepted teleport requests charge on completed teleport instead. `/tpa` does not charge for sending a request and does not begin its timer until acceptance. The built-in teleport and generic rule do not charge twice for the same action.

Messages and rule changes apply through `/economy reload`. Changing command enablement requires a restart so disabled names and aliases can be released cleanly.
