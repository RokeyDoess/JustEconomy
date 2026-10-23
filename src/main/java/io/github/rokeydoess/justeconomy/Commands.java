package io.github.rokeydoess.justeconomy;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.nio.file.*;
import java.util.*;

final class Commands implements Listener {
    private record Request(Player from, Player to, String line, long expires) { }
    private record Confirmation(EssentialsImport.Preview preview, long expires, int precision, long maximum) { }
    private final JustEconomyPlugin plugin;
    private final Actions actions;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<String, Confirmation> confirmations = new HashMap<>();
    private final Set<String> importing = new HashSet<>();
    private final BukkitTask expiry;
    private Location spawn;

    Commands(JustEconomyPlugin plugin, Actions actions) throws Exception {
        this.plugin = plugin; this.actions = actions;
        File file = new File(plugin.getDataFolder(), "spawn.yml");
        if (file.exists()) {
            try {
                YamlConfiguration yaml = Settings.yaml(file);
                World world = Bukkit.getWorld(UUID.fromString(yaml.getString("world")));
                if (world != null) {
                    for (String key : List.of("x", "y", "z", "yaw", "pitch")) if (!(yaml.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("Invalid spawn coordinate: " + key);
                    spawn = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"), (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
                } else plugin.getLogger().warning("Saved spawn world is not loaded. Set spawn again after loading that world.");
            } catch (Exception e) { plugin.getLogger().warning("Saved spawn is unavailable: " + e.getMessage() + ". Use /setspawn to replace it."); }
        }
        expiry = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Request request : List.copyOf(requests.values())) if (request.expires() <= now) {
                requests.remove(request.from().getUniqueId()); Player from = request.from();
                if (from != null) plugin.messages().send(from, "request-expired");
            }
            confirmations.entrySet().removeIf(e -> e.getValue().expires() <= now);
        }, 20, 20);
    }

    Command create(String name, List<String> aliases) {
        return new Command(name, "JustEconomy " + name, usage(name), aliases) {
            { setPermission("justeconomy." + name); }
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                if (!sender.hasPermission(getPermission())) { plugin.messages().send(sender, "no-permission"); return true; }
                String line = name + (args.length == 0 ? "" : " " + String.join(" ", args));
                Runnable execute = () -> {
                    try { run(sender, name, args, line); }
                    catch (Exception e) { plugin.error(sender, e); }
                };
                if (sender instanceof Player player && !Set.of("spawn", "tpa").contains(name)) {
                    CommandRules.Policy policy = plugin.settings().rules().match(line, CommandIdentity::same);
                    if (policy != null) actions.gate(player, policy, () -> player.hasPermission(getPermission()), execute);
                    else execute.run();
                } else execute.run();
                return true;
            }
            @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (!sender.hasPermission(getPermission())) return List.of();
                if (name.equals("economy") && args.length == 1) return filter(List.of("give", "take", "set", "reload", "backup", "import"), args[0]);
                if (name.equals("economy") && args.length == 2 && args[0].equalsIgnoreCase("import")) return filter(List.of("preview", "confirm"), args[1]);
                if ((args.length == 1 && Set.of("pay", "balance", "tpa", "tpaccept", "tpdeny").contains(name)) || (name.equals("economy") && args.length == 2 && Set.of("give", "take", "set").contains(args[0])))
                    return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length - 1]);
                return List.of();
            }
        };
    }

    private static List<String> filter(List<String> values, String prefix) { return values.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).sorted().toList(); }
    private static String usage(String name) {
        return switch (name) {
            case "balance" -> "/balance [player]";
            case "balancetop" -> "/balancetop [page]";
            case "pay" -> "/pay <player> <amount>";
            case "tpa" -> "/tpa <player>";
            case "tpaccept", "tpdeny" -> "/" + name + " [player]";
            case "economy" -> "/economy <give|take|set> <player> <amount> | reload | backup | import <preview|confirm>";
            default -> "/" + name;
        };
    }

    private void run(CommandSender sender, String name, String[] args, String line) throws Exception {
        switch (name) {
            case "balance" -> {
                if (args.length > 1 || (args.length == 0 && !(sender instanceof Player))) { usage(sender, name); return; }
                if (args.length == 1 && !sender.hasPermission("justeconomy.balance.others")) { plugin.messages().send(sender, "no-permission"); return; }
                UUID id = args.length == 0 ? ((Player) sender).getUniqueId() : plugin.resolve(args[0]);
                if (id == null) { plugin.messages().send(sender, "unknown-player"); return; }
                if (sender instanceof Player p && id.equals(p.getUniqueId())) plugin.ensure(p);
                plugin.messages().send(sender, "balance", "name", args.length == 0 ? sender.getName() : args[0], "amount", plugin.money(plugin.ledger().balance(id)));
            }
            case "balancetop" -> {
                if (args.length > 1) { usage(sender, name); return; }
                int page;
                try { page = args.length == 0 ? 1 : Integer.parseInt(args[0]); } catch (NumberFormatException e) { usage(sender, name); return; }
                int size = plugin.settings().pageSize();
                List<Ledger.Account> top = plugin.ledger().top(page, size);
                plugin.messages().send(sender, "leaderboard-title", "page", page);
                if (top.isEmpty()) plugin.messages().send(sender, "leaderboard-empty");
                for (int i = 0; i < top.size(); i++) {
                    Ledger.Account a = top.get(i);
                    plugin.messages().send(sender, "leaderboard-entry", "rank", ((long) page - 1) * size + i + 1, "name", a.name().isBlank() ? a.id() : a.name(), "amount", plugin.money(a.balance()));
                }
            }
            case "pay" -> {
                Player from = player(sender); if (from == null) return;
                if (args.length != 2) { usage(sender, name); return; }
                Player online = Bukkit.getPlayerExact(args[0]);
                UUID to = online != null ? online.getUniqueId() : plugin.resolve(args[0]);
                if (to == null) { plugin.messages().send(sender, "unknown-player"); return; }
                if (from.getUniqueId().equals(to)) { plugin.messages().send(sender, "self-target"); return; }
                if ((!plugin.settings().offlinePay() || plugin.settings().payRange() > 0) && online == null) { plugin.messages().send(sender, "online-only"); return; }
                double range = plugin.settings().payRange();
                if (range > 0 && !Payments.inRange(from.getLocation(), online.getLocation(), range)) { plugin.messages().send(sender, "out-of-range", "range", range); return; }
                long amount = plugin.settings().money().parse(args[1]);
                if (amount == 0) { plugin.messages().send(sender, "positive-amount"); return; }
                plugin.ensure(from);
                if (!plugin.ledger().exists(to)) plugin.ensure(Bukkit.getOfflinePlayer(to));
                plugin.ledger().transfer(from.getUniqueId(), to, amount);
                plugin.messages().send(from, "paid", "amount", plugin.money(amount), "name", args[0]);
                if (online != null) plugin.messages().send(online, "received", "amount", plugin.money(amount), "name", from.getName());
            }
            case "setspawn" -> {
                Player player = player(sender); if (player == null) return;
                if (args.length != 0) { usage(sender, name); return; }
                Location next = player.getLocation().clone();
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("world", next.getWorld().getUID().toString());
                yaml.set("x", next.getX()); yaml.set("y", next.getY()); yaml.set("z", next.getZ()); yaml.set("yaw", next.getYaw()); yaml.set("pitch", next.getPitch());
                Path destination = plugin.getDataFolder().toPath().resolve("spawn.yml");
                Path temporary = Files.createTempFile(plugin.getDataFolder().toPath(), "spawn-", ".tmp");
                try {
                    yaml.save(temporary.toFile());
                    try { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temporary); }
                spawn = next; plugin.messages().send(sender, "spawn-saved");
            }
            case "spawn" -> {
                Player player = player(sender); if (player == null) return;
                if (args.length != 0) { usage(sender, name); return; }
                if (spawn == null || Bukkit.getWorld(spawn.getWorld().getUID()) == null) { plugin.messages().send(sender, "spawn-missing"); return; }
                CommandRules.Policy policy = policy(line, plugin.settings().spawn());
                actions.teleport(player, policy, () -> player.hasPermission("justeconomy.spawn") && spawn != null, () -> spawn.clone());
            }
            case "tpa" -> request(player(sender), args, line);
            case "tpaccept", "tpdeny" -> answer(player(sender), args, name.equals("tpaccept"));
            case "tpacancel" -> {
                Player player = player(sender); if (player == null) return;
                if (args.length != 0) { usage(sender, name); return; }
                Request request = requests.remove(player.getUniqueId());
                if (request != null) notifyEnded(request);
                actions.cancel(player);
            }
            case "economy" -> admin(sender, args);
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }

    private CommandRules.Policy policy(String line, CommandRules.Policy fallback) {
        CommandRules.Policy matched = plugin.settings().rules().match(line, CommandIdentity::same);
        return matched == null ? fallback : matched;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        plugin.messages().send(sender, "player-only"); return null;
    }
    private void usage(CommandSender sender, String name) { plugin.messages().send(sender, "usage", "usage", usage(name)); }

    private void request(Player from, String[] args, String line) {
        if (from == null) return;
        if (args.length != 1) { usage(from, "tpa"); return; }
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null) { plugin.messages().send(from, "online-only"); return; }
        if (to.equals(from)) { plugin.messages().send(from, "self-target"); return; }
        if (actions.busy(from) || requests.containsKey(from.getUniqueId())) { plugin.messages().send(from, "pending"); return; }
        if (!plugin.settings().crossWorld() && !from.getWorld().equals(to.getWorld())) { plugin.messages().send(from, "cross-world"); return; }
        int seconds = plugin.settings().expirySeconds();
        Request request = new Request(from, to, line, System.currentTimeMillis() + seconds * 1000L);
        requests.put(from.getUniqueId(), request);
        plugin.messages().send(from, "request-sent", "name", to.getName(), "seconds", seconds);
        plugin.messages().send(to, "request-received", "name", from.getName());
    }

    private void answer(Player to, String[] args, boolean accept) {
        if (to == null) return;
        if (args.length > 1) { usage(to, accept ? "tpaccept" : "tpdeny"); return; }
        List<Request> matches = requests.values().stream().filter(r -> r.to().getUniqueId().equals(to.getUniqueId()) && r.expires() > System.currentTimeMillis())
                .filter(r -> args.length == 0 || r.from().getName().equalsIgnoreCase(args[0])).toList();
        if (matches.size() != 1) { plugin.messages().send(to, "request-missing"); return; }
        Request request = matches.getFirst(); Player from = request.from().isOnline() ? request.from() : null;
        if (accept && from != null && actions.busy(from)) { plugin.messages().send(to, "pending"); return; }
        requests.remove(request.from().getUniqueId());
        if (!accept || from == null) { notifyEnded(request); return; }
        actions.teleport(from, policy(request.line(), plugin.settings().tpa()),
                () -> from.isOnline() && to.isOnline() && !from.isDead() && !to.isDead() && from.hasPermission("justeconomy.tpa") && to.hasPermission("justeconomy.tpaccept"), to::getLocation);
    }

    private void notifyEnded(Request request) {
        Player from = request.from(), to = request.to();
        if (from != null) plugin.messages().send(from, "request-ended");
        if (to != null) plugin.messages().send(to, "request-ended");
    }

    private void admin(CommandSender sender, String[] args) throws Exception {
        if (args.length == 0) { usage(sender, "economy"); return; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && args.length == 1) { plugin.reloadSettings(); plugin.messages().send(sender, "reload"); return; }
        if (sub.equals("backup") && args.length == 1) {
            Path backup = plugin.ledger().backup(backupPath()); plugin.messages().send(sender, "backup", "file", backup.getFileName()); return;
        }
        if (sub.equals("import") && args.length == 2) { importCommand(sender, args[1]); return; }
        if (!Set.of("give", "take", "set").contains(sub) || args.length != 3) { usage(sender, "economy"); return; }
        UUID id = plugin.resolve(args[1]);
        if (id == null) { plugin.messages().send(sender, "unknown-player"); return; }
        if (!plugin.ledger().exists(id)) plugin.ensure(Bukkit.getOfflinePlayer(id));
        long amount = plugin.settings().money().parse(args[2]);
        switch (sub) {
            case "give" -> plugin.ledger().deposit(id, amount);
            case "take" -> plugin.ledger().withdraw(id, amount);
            case "set" -> plugin.ledger().set(id, amount);
        }
        plugin.messages().send(sender, "balance-updated", "name", args[1], "amount", plugin.money(plugin.ledger().balance(id)));
    }

    private Path backupPath() { return plugin.getDataFolder().toPath().resolve("backups/balances-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".db"); }

    private void importCommand(CommandSender sender, String mode) {
        String key = sender instanceof Player p ? p.getUniqueId().toString() : sender.getName();
        if (!mode.equalsIgnoreCase("preview") && !mode.equalsIgnoreCase("confirm")) { usage(sender, "economy"); return; }
        if (importing.contains(key)) { plugin.messages().send(sender, "pending"); return; }
        Confirmation confirmation = mode.equalsIgnoreCase("confirm") ? confirmations.remove(key) : null;
        if (mode.equalsIgnoreCase("confirm") && (confirmation == null || confirmation.expires() <= System.currentTimeMillis())) { plugin.messages().send(sender, "import-missing"); return; }
        importing.add(key);
        Settings settings = plugin.settings();
        Ledger ledger = plugin.ledger();
        Path source = plugin.getDataFolder().toPath().getParent().resolve("Essentials/userdata");
        if (confirmation == null) plugin.messages().send(sender, "import-warning");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EssentialsImport.Preview preview = EssentialsImport.read(source, settings.money(), settings.maximum());
                int existing = 0;
                for (Ledger.ImportRow row : preview.rows()) if (ledger.exists(row.id()) || ledger.imported(row.id())) existing++;
                int skipped = existing;
                if (confirmation == null) {
                    main(() -> {
                        importing.remove(key);
                        confirmations.put(key, new Confirmation(preview, System.currentTimeMillis() + 60_000, settings.money().precision(), settings.maximum()));
                        plugin.messages().send(sender, "import-preview", "ready", preview.rows().size() - skipped, "existing", skipped, "invalid", preview.invalid());
                    });
                } else {
                    if (!confirmation.preview().fingerprint().equals(preview.fingerprint()) || confirmation.precision() != settings.money().precision() || confirmation.maximum() != settings.maximum()) {
                        main(() -> { importing.remove(key); plugin.messages().send(sender, "import-changed"); }); return;
                    }
                    Path backup = ledger.backup(backupPath());
                    Ledger.ImportResult result = ledger.importBalances(preview.rows(), preview.fingerprint());
                    main(() -> {
                        importing.remove(key);
                        plugin.messages().send(sender, "import-complete", "imported", result.imported(), "existing", result.existing(), "file", backup.getFileName());
                    });
                }
            } catch (Exception e) { main(() -> { importing.remove(key); plugin.error(sender, e); }); }
        });
    }

    private void main(Runnable run) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, run); }
    void clearPreviews() { confirmations.clear(); }
    void cancelRequests() { for (Request request : requests.values()) notifyEnded(request); requests.clear(); }
    void close() { expiry.cancel(); cancelRequests(); confirmations.clear(); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Request request : List.copyOf(requests.values())) if (request.from().getUniqueId().equals(id) || request.to().getUniqueId().equals(id)) { requests.remove(request.from().getUniqueId()); notifyEnded(request); }
        confirmations.remove(id.toString());
    }
}
