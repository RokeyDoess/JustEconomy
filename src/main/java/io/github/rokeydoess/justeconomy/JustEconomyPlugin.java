package io.github.rokeydoess.justeconomy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class JustEconomyPlugin extends JavaPlugin implements Listener {
    private volatile Settings settings;
    private volatile Messages messages;
    private Ledger ledger;
    private VaultEconomy economy;
    private Actions actions;
    private Commands commands;
    private final AtomicLong lastStorageLog = new AtomicLong();
    private final Set<Command> registered = new HashSet<>();

    @Override public void onLoad() {
        getLifecycleManager().registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> CommandIdentity.dispatcher = event.registrar().getDispatcher());
    }

    @Override public void onEnable() {
        try {
            for (String name : List.of("config.yml", "command-rules.yml", "messages.yml")) if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
            settings = Settings.load(getDataFolder());
            messages = new Messages(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"));
            if (getServer().getServicesManager().getRegistration(Economy.class) != null) throw new IllegalStateException("Another Vault economy provider is registered. Disable it before enabling JustEconomy; import balances explicitly if needed.");
            ledger = new Ledger(getDataFolder().toPath().resolve("balances.db"), settings.money().precision(), settings.maximum());
            actions = new Actions(this);
            commands = new Commands(this, actions);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(actions, this);
            getServer().getPluginManager().registerEvents(commands, this);
            for (Map.Entry<String, Boolean> entry : settings.commands().entrySet()) {
                if (!entry.getValue()) continue;
                Command command = commands.create(entry.getKey(), Settings.COMMANDS.get(entry.getKey()));
                boolean primary = getServer().getCommandMap().register("justeconomy", command);
                registered.add(command);
                if (!primary) getLogger().warning("/" + entry.getKey() + " belongs to another plugin; use /justeconomy:" + entry.getKey() + " or disable the competing command.");
            }
            economy = new VaultEconomy(this);
            getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Normal);
            for (Player player : getServer().getOnlinePlayers()) ensure(player);
            getLogger().info("Economy ready. Balances are stored in balances.db.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not enable JustEconomy: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (actions != null) actions.close();
        if (commands != null) commands.close();
        getServer().getServicesManager().unregisterAll(this);
        CommandMap map = getServer().getCommandMap();
        List<String> labels = map.getKnownCommands().entrySet().stream().filter(e -> registered.contains(e.getValue())).map(Map.Entry::getKey).toList();
        for (String label : labels) map.getKnownCommands().remove(label);
        for (Command command : registered) command.unregister(map);
        registered.clear();
        if (ledger != null) try { ledger.close(); } catch (SQLException e) { getLogger().log(Level.SEVERE, "Could not close balance storage", e); }
        ledger = null;
    }

    public Settings settings() { return settings; }
    public Messages messages() { return messages; }
    public Ledger ledger() { return ledger; }
    String money(long units) { return settings.money().format(units); }
    boolean owns(Command command) { return registered.contains(command); }

    public void ensure(OfflinePlayer player) throws SQLException { ledger.account(player.getUniqueId(), player.getName(), settings.initial()); }

    public UUID resolve(String name) throws SQLException {
        if (name == null || name.isBlank()) return null;
        Player online = getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID saved = ledger.find(name);
        if (saved != null) return saved;
        OfflinePlayer cached = getServer().getOfflinePlayerIfCached(name);
        return cached != null && cached.hasPlayedBefore() ? cached.getUniqueId() : null;
    }

    public void reloadSettings() throws Exception {
        Settings next = Settings.load(getDataFolder());
        Messages nextMessages = new Messages(new File(getDataFolder(), "messages.yml"), getResource("messages.yml"));
        if (next.money().precision() != settings.money().precision()) throw new IllegalArgumentException("economy.precision cannot change for an existing database");
        if (actions.completing()) throw new IllegalArgumentException("Wait for in-progress teleports to finish before reloading");
        ledger.maximum(next.maximum());
        actions.cancelAll(); commands.cancelRequests(); commands.clearPreviews();
        settings = next; messages = nextMessages;
    }

    void error(CommandSender sender, Exception e) {
        if (e instanceof IllegalArgumentException) messages.send(sender, "error", "reason", e.getMessage());
        else { storageError(e); messages.send(sender, "storage-error"); }
    }

    void storageError(Exception e) {
        long now = System.currentTimeMillis(), previous = lastStorageLog.get();
        if (now - previous > 30_000 && lastStorageLog.compareAndSet(previous, now)) getLogger().log(Level.SEVERE, "Economy operation failed; balances were not confirmed", e);
    }

    @EventHandler public void joined(PlayerJoinEvent event) { try { ensure(event.getPlayer()); } catch (Exception e) { error(event.getPlayer(), e); } }
    @EventHandler public void services(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == Economy.class) getServer().getScheduler().runTask(this, this::checkProvider);
    }
    @EventHandler public void loaded(ServerLoadEvent event) { checkProvider(); }
    private void checkProvider() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider != null && provider.getProvider() != economy) {
            getLogger().severe("Another economy provider became active: " + provider.getPlugin().getName() + ". Disabling JustEconomy to avoid split balances.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }
}
