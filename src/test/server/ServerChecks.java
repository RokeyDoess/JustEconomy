package checks;

import io.github.rokeydoess.justeconomy.*;
import io.github.rokeydoess.justjobs.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Integration checks for the disposable server created by scripts/check-server.ps1. */
public final class ServerChecks extends JavaPlugin implements Listener {
    private static final UUID OAK = UUID.nameUUIDFromBytes("economy-check-oak".getBytes(StandardCharsets.UTF_8));
    private static final UUID BIRCH = UUID.nameUUIDFromBytes("economy-check-birch".getBytes(StandardCharsets.UTF_8));
    private static final UUID JOBS = UUID.nameUUIDFromBytes("economy-check-jobs".getBytes(StandardCharsets.UTF_8));
    private static final UUID IMPORT = UUID.nameUUIDFromBytes("economy-check-import".getBytes(StandardCharsets.UTF_8));
    private JustEconomyPlugin plugin;
    private JustJobsPlugin jobs;
    private Economy economy;
    private World world;
    private State oak, birch;
    private int checks, dispatches;
    private boolean blockCommands, blockTeleports;
    private final int phase = Integer.getInteger("justeconomy.check.phase", 1);
    private long before;

    private final class State {
        final UUID id; final String name; final Player player;
        Location location; boolean online = true, allowed = true; int teleports;
        State(UUID id, String name) {
            this.id = id; this.name = name; location = new Location(world, 4.5, 101, 4.5);
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "toString" -> name;
                case "getServer" -> Bukkit.getServer();
                case "getGameMode" -> GameMode.SURVIVAL;
                case "getWorld" -> location.getWorld();
                case "getLocation" -> location.clone();
                case "isOnline" -> online;
                case "hasPlayedBefore", "isOnGround" -> true;
                case "hasPermission" -> !String.valueOf(args[0]).startsWith("justeconomy.bypass.") && (!String.valueOf(args[0]).equals("checks.allowed") || allowed);
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "teleportAsync" -> {
                    Location target = ((Location) args[0]).clone();
                    PlayerTeleportEvent event = new PlayerTeleportEvent((Player) proxy, location, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) yield CompletableFuture.completedFuture(false);
                    location = event.getTo().clone(); teleports++;
                    yield CompletableFuture.completedFuture(true);
                }
                default -> empty(method.getReturnType());
            });
        }
    }

    private static Object empty(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        return 0;
    }

    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); later(30, this::start); }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private void later(long ticks, Checked work) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { work.run(); }
            catch (Throwable e) { getLogger().log(java.util.logging.Level.SEVERE, "SERVER CHECKS FAILED", e); Bukkit.shutdown(); }
        }, ticks);
    }
    private void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++; getLogger().info("PASS: " + label);
    }
    private void await(BooleanSupplier condition, Checked next) { await(condition, next, 100); }
    private void await(BooleanSupplier condition, Checked next, int attempts) {
        later(2, () -> { if (condition.getAsBoolean()) next.run(); else if (attempts > 0) await(condition, next, attempts - 1); else throw new AssertionError("Timed out waiting for asynchronous work"); });
    }
    private long balance() throws Exception { return plugin.ledger().balance(OAK); }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private void command(Player sender, String name, String... args) {
        Command command = Bukkit.getCommandMap().getCommand("justeconomy:" + name);
        if (command == null) throw new AssertionError("Command missing: " + name);
        command.execute(sender, name, args);
    }
    private void input(String line) { Bukkit.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(oak.player, "/" + line)); }

    private void start() throws Exception {
        plugin = (JustEconomyPlugin) Bukkit.getPluginManager().getPlugin("JustEconomy");
        jobs = (JustJobsPlugin) Bukkit.getPluginManager().getPlugin("JustJobs");
        check(plugin != null && plugin.isEnabled(), "JustEconomy enables on Paper with VaultUnlocked");
        check(jobs != null && jobs.isEnabled(), "JustJobs enables alongside JustEconomy");
        economy = Bukkit.getServicesManager().load(Economy.class);
        check(economy != null && economy.getName().equals("JustEconomy"), "Vault selects the JustEconomy provider");
        world = Bukkit.getWorlds().getFirst();
        oak = new State(OAK, "Oak"); birch = new State(BIRCH, "Birch");
        if (phase == 2) { restartChecks(); return; }
        if (phase == 3) { providerConflict(); return; }
        check(Bukkit.getCommandMap().getCommand("balancetop") == null && Bukkit.getCommandMap().getCommand("justeconomy:balancetop") == null && Bukkit.getCommandMap().getCommand("baltop") == null, "Disabled command and all aliases stay unregistered");
        check(Bukkit.getCommandMap().getCommand("balance") == Bukkit.getCommandMap().getCommand("bal"), "Balance alias resolves to the same command");
        check(economy.fractionalDigits() == 4 && !economy.hasBankSupport(), "Vault exposes precision and bank capabilities accurately");
        Class<?> identities = Class.forName("io.github.rokeydoess.justeconomy.CommandIdentity", true, plugin.getClass().getClassLoader());
        Method same = identities.getDeclaredMethod("same", String.class, String.class); same.setAccessible(true);
        check((boolean) same.invoke(null, "give", "minecraft:give"), "Vanilla namespaces resolve to the same command identity");
        check(!(boolean) same.invoke(null, "give", "minecraft:kill"), "Unrelated vanilla commands keep separate identities");
        check(economy.createPlayerAccount(oak.player) && economy.createPlayerAccount(birch.player), "Vault creates UUID accounts");
        check(economy.depositPlayer(oak.player, 0.0085).transactionSuccess(), "Vault accepts small fractional job earnings");
        check(plugin.ledger().balance(OAK) == 85, "Fractional deposit is stored exactly");
        check(economy.format(economy.getBalance(oak.player)).equals("$0.01") && economy.fractionalDigits() == 4, "Vault displays two decimals while retaining four-decimal accounting");
        Path displayConfig = plugin.getDataFolder().toPath().resolve("config.yml");
        String displayDefaults = Files.readString(displayConfig);
        Files.writeString(displayConfig, displayDefaults.replace("display-precision: 2", "display-precision: 4"));
        plugin.reloadSettings();
        check(economy.format(economy.getBalance(oak.player)).equals("$0.0085") && plugin.ledger().balance(OAK) == 85, "Display precision reloads without changing the saved balance");
        Files.writeString(displayConfig, displayDefaults); plugin.reloadSettings();
        check(!economy.depositPlayer(oak.player, Double.NaN).transactionSuccess() && !economy.withdrawPlayer(oak.player, -1).transactionSuccess(), "Invalid Vault amounts fail without changing balances");
        plugin.ledger().set(OAK, 1_000_000); plugin.ledger().set(BIRCH, 1_000_000);
        check(economy.getBalance(oak.player, "other-world") == 100, "World overloads share the global balance");
        Command fixture = new Command("checkcharge", "Command charging fixture", "/checkcharge", List.of("checkfee")) {
            { setPermission("checks.allowed"); }
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                check(sender == oak.player, "External command retains the original sender"); dispatches++; return true;
            }
        };
        Bukkit.getCommandMap().register("checks", fixture);
        input("checks:checkcharge");
        later(4, () -> {
            check(balance() == 980_000 && dispatches == 1, "Namespaced external command charges exactly once before dispatch (balance=" + balance() + ", dispatches=" + dispatches + ")");
            input("checkfee market");
            later(4, this::cancelMovement);
        });
    }

    private void cancelMovement() throws Exception {
        Location rotated = oak.location.clone(); rotated.setYaw(90);
        Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(oak.player, oak.location, rotated));
        check(((Map<?, ?>) field(field(plugin, "actions"), "pending")).containsKey(OAK), "Looking around does not cancel standstill");
        Location moved = oak.location.clone().add(1, 0, 0);
        Bukkit.getPluginManager().callEvent(new PlayerMoveEvent(oak.player, oak.location, moved));
        later(25, () -> {
            check(balance() == 980_000 && dispatches == 1, "Movement cancels warmup without a fee or dispatch");
            input("checkfee market");
            later(25, () -> {
                check(balance() == 950_000 && dispatches == 2, "Longest subcommand rule wins through an alias");
                input("checkcharge market");
                later(4, () -> {
                    Bukkit.getPluginManager().callEvent(new EntityDamageEvent(oak.player, EntityDamageEvent.DamageCause.CUSTOM,
                            org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), 1));
                    later(25, this::cancelPermission);
                });
            });
        });
    }

    private void cancelPermission() throws Exception {
        check(balance() == 950_000 && dispatches == 2, "Damage cancels a paid warmup without charging");
        input("checkcharge market");
        later(4, () -> {
            oak.allowed = false;
            later(25, () -> {
                check(balance() == 950_000 && dispatches == 2, "Revoked permission prevents delayed execution and charging");
                oak.allowed = true; blockCommands = true; input("checkcharge");
                later(4, () -> {
                    blockCommands = false;
                    check(balance() == 950_000 && dispatches == 2, "Other plugin cancellation is respected before debit");
                    teleportSuccess();
                });
            });
        });
    }

    private void platform() {
        world.getChunkAt(0, 0).addPluginChunkTicket(this);
        for (int x = 2; x <= 7; x++) for (int z = 2; z <= 7; z++) {
            world.getBlockAt(x, 100, z).setType(Material.STONE, false);
            world.getBlockAt(x, 101, z).setType(Material.AIR, false);
            world.getBlockAt(x, 102, z).setType(Material.AIR, false);
        }
    }
    private void teleportSuccess() throws Exception {
        platform(); command(oak.player, "setspawn"); before = balance(); command(oak.player, "spawn");
        later(30, () -> {
            check(oak.teleports == 1 && balance() == before - 12_500, "Completed spawn teleport charges once after warmup");
            before = balance(); blockTeleports = true; command(oak.player, "spawn");
            later(30, () -> {
                blockTeleports = false;
                check(oak.teleports == 1 && balance() == before, "Cancelled teleport returns the entire reservation");
                world.getBlockAt(4, 100, 4).setType(Material.LAVA, false);
                command(oak.player, "spawn");
                later(30, () -> {
                    check(oak.teleports == 1 && balance() == before, "Unsafe destination is rejected without keeping a fee");
                    platform(); teleportRequest();
                });
            });
        });
    }

    @SuppressWarnings("unchecked") private void teleportRequest() throws Exception {
        Object commands = field(plugin, "commands");
        Class<?> requestClass = Class.forName("io.github.rokeydoess.justeconomy.Commands$Request", true, plugin.getClass().getClassLoader());
        Constructor<?> constructor = requestClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object request = constructor.newInstance(oak.player, birch.player, "tpa Birch", System.currentTimeMillis() + 60_000);
        ((Map<UUID, Object>) field(commands, "requests")).put(OAK, request);
        before = balance(); int previousTeleports = oak.teleports; long recipientBalance = plugin.ledger().balance(BIRCH);
        command(birch.player, "tpaccept", "Oak");
        later(30, () -> {
            check(oak.teleports == previousTeleports + 1 && balance() == before - 25_000, "Accepted tpa warms up and charges the requester on completion");
            check(plugin.ledger().balance(BIRCH) == recipientBalance, "Accepting a teleport does not charge the recipient");
            jobsChecks();
        });
    }

    private void jobsChecks() throws Exception {
        State worker = new State(JOBS, "Cedar");
        jobs.settings().set("economy.whole-number-payouts", false);
        jobs.award(worker.player, JobRegistry.Activity.BLOCK_BREAK, Material.DIRT, null, 1);
        await(() -> economy.getBalance(Bukkit.getOfflinePlayer(JOBS)) > 0, () -> {
            check(plugin.ledger().balance(JOBS) == 85, "JustJobs pays a fractional earning through the real Vault provider");
            jobs.settings().set("economy.whole-number-payouts", true);
            jobs.award(worker.player, JobRegistry.Activity.BLOCK_BREAK, Material.DIRT, null, 118);
            await(() -> economy.getBalance(Bukkit.getOfflinePlayer(JOBS)) > 1, () -> {
                check(plugin.ledger().balance(JOBS) == 10_085, "JustJobs whole-number payout preserves the existing fractional balance");
                check(jobs.profile(worker.player).pending.compareTo(new java.math.BigDecimal("0.003")) == 0, "JustJobs keeps its fractional remainder");
                importChecks();
            });
        });
    }

    private void importChecks() throws Exception {
        Path source = plugin.getDataFolder().toPath().getParent().resolve("Essentials/userdata"); Files.createDirectories(source);
        Files.writeString(source.resolve(IMPORT + ".yml"), "last-account-name: Maple\nmoney: '7.0085'\n");
        Files.writeString(source.resolve(OAK + ".yml"), "last-account-name: Oak\nmoney: '999'\n");
        Bukkit.getCommandMap().getCommand("economy").execute(Bukkit.getConsoleSender(), "economy", new String[]{"import", "preview"});
        Object commands = field(plugin, "commands");
        await(() -> { try { return !((Map<?, ?>) field(commands, "confirmations")).isEmpty(); } catch (Exception e) { throw new RuntimeException(e); } }, () -> {
            before = balance();
            Bukkit.getCommandMap().getCommand("economy").execute(Bukkit.getConsoleSender(), "economy", new String[]{"import", "confirm"});
            await(() -> economy.getBalance(Bukkit.getOfflinePlayer(IMPORT)) > 0, () -> {
                check(plugin.ledger().balance(IMPORT) == 70_085 && balance() == before, "Essentials import creates new accounts and preserves existing balances");
                try (var backups = Files.list(plugin.getDataFolder().toPath().resolve("backups"))) { check(backups.findAny().isPresent(), "Import creates a database backup"); }
                paymentChecks();
            });
        });
    }

    private void paymentChecks() throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(config);
        long from = balance(), to = plugin.ledger().balance(BIRCH);
        command(oak.player, "pay", "Birch", "0.0001");
        check(balance() == from && plugin.ledger().balance(BIRCH) == to, "Default payment policy rejects offline recipients");
        Files.writeString(config, original.replace("allow-offline: false", "allow-offline: true")); plugin.reloadSettings();
        command(oak.player, "pay", "Birch", "0.0001");
        check(balance() == from - 1 && plugin.ledger().balance(BIRCH) == to + 1, "Configured offline payment transfers the exact fractional amount");
        Files.writeString(config, original.replace("allow-offline: false", "allow-offline: true").replace("range: 0", "range: 5")); plugin.reloadSettings();
        from = balance(); to = plugin.ledger().balance(BIRCH);
        command(oak.player, "pay", "Birch", "1");
        check(balance() == from && plugin.ledger().balance(BIRCH) == to, "A range limit still requires online recipients when offline payments are enabled");
        Files.writeString(config, original); plugin.reloadSettings();
        loadChecks();
    }

    private void loadChecks() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            UUID id = new UUID(5, i); plugin.ledger().account(id, "Saved" + i, 10_000); accounts.add(id);
        }
        List<Long> times = new ArrayList<>();
        loadTick(accounts, times, 0);
    }

    private void loadTick(List<UUID> accounts, List<Long> times, int tick) {
        later(1, () -> {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                int from = (tick * 10 + i) % accounts.size(), to = (from + 1) % accounts.size();
                plugin.ledger().transfer(accounts.get(from), accounts.get(to), 1);
            }
            if (tick % 10 == 0) plugin.ledger().top(1, 10);
            times.add(System.nanoTime() - start);
            if (tick < 99) loadTick(accounts, times, tick + 1);
            else {
                long sum = 0;
                for (UUID id : accounts) sum += plugin.ledger().balance(id);
                check(sum == 10_000_000, "1,000 durable transfers preserve the total across 1,000 saved accounts");
                times.sort(Long::compareTo);
                getLogger().info(String.format(Locale.ROOT, "LOAD SAMPLE: ten durable transfers per tick; batch p50 %.3f ms, p95 %.3f ms, max %.3f ms", times.get(50) / 1e6, times.get(95) / 1e6, times.getLast() / 1e6));
                reloadAndPersistence();
            }
        });
    }

    private void reloadAndPersistence() throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        String original = Files.readString(config);
        Files.writeString(config, original.replace("precision: 4", "precision: 2"));
        try { plugin.reloadSettings(); throw new AssertionError("Invalid reload was accepted"); }
        catch (IllegalArgumentException expected) { check(plugin.settings().money().precision() == 4, "Invalid reload retains working settings"); }
        Files.writeString(config, original.replace("balance: true", "balance: false"));
        plugin.reloadSettings();
        check(Bukkit.getCommandMap().getCommand("balance") != null, "Command registration changes wait for a restart");
        Properties state = new Properties();
        state.setProperty("oak", Long.toString(balance())); state.setProperty("birch", Long.toString(plugin.ledger().balance(BIRCH)));
        Files.createDirectories(getDataFolder().toPath());
        try (var out = Files.newOutputStream(getDataFolder().toPath().resolve("expected.properties"))) { state.store(out, "Saved balance expectations"); }
        plugin.ledger().reserve(OAK, 100);
        Files.writeString(plugin.getDataFolder().toPath().resolve("spawn.yml"), "world: invalid-world-id\n");
        pass();
    }

    private void restartChecks() throws Exception {
        Properties expected = new Properties();
        try (var input = Files.newInputStream(getDataFolder().toPath().resolve("expected.properties"))) { expected.load(input); }
        check(plugin.ledger().balance(OAK) == Long.parseLong(expected.getProperty("oak")), "Restart recovers an unfinished fee reservation");
        check(plugin.ledger().balance(BIRCH) == Long.parseLong(expected.getProperty("birch")), "Committed recipient balance survives restart");
        check(Bukkit.getCommandMap().getCommand("balance") == null && Bukkit.getCommandMap().getCommand("bal") == null && Bukkit.getCommandMap().getCommand("justeconomy:balance") == null, "Restart applies disabled registration including namespaced commands");
        check(economy.isEnabled(), "Economy remains available with player commands disabled");
        check(economy.isEnabled() && field(field(plugin, "commands"), "spawn") == null, "Malformed optional spawn data does not disable the economy");
        Ledger.ImportResult repeat = plugin.ledger().importBalances(List.of(new Ledger.ImportRow(IMPORT, "Maple", 70_085)), "repeat");
        check(repeat.imported() == 0 && plugin.ledger().balance(IMPORT) == 70_085, "Repeated import cannot duplicate a prior imported balance");
        pass();
    }

    private void providerConflict() {
        Economy competitor = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (p, m, a) -> m.getName().equals("getName") ? "ConflictFixture" : empty(m.getReturnType()));
        Bukkit.getServicesManager().register(Economy.class, competitor, this, ServicePriority.Highest);
        later(4, () -> {
            check(!plugin.isEnabled(), "A competing selected provider disables JustEconomy instead of splitting balances");
            check(Bukkit.getCommandMap().getCommand("economy") == null, "Disabling the provider releases its command registrations");
            pass();
        });
    }

    private void pass() { getLogger().info("SERVER CHECKS PHASE " + phase + " PASS: " + checks + " checks"); Bukkit.shutdown(); }
    @EventHandler(priority = EventPriority.HIGHEST) public void block(PlayerCommandPreprocessEvent event) { if (blockCommands && event.getMessage().contains("checkcharge")) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void blockTeleport(PlayerTeleportEvent event) { if (blockTeleports) event.setCancelled(true); }
}
