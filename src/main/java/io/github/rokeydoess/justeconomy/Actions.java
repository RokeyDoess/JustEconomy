package io.github.rokeydoess.justeconomy;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.function.BooleanSupplier;

final class Actions implements Listener {
    private record Pending(Player player, Location origin, BooleanSupplier valid, Runnable run, long due) { }
    private final JustEconomyPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> completing = new HashSet<>();
    private final Map<UUID, BooleanSupplier> completingChecks = new HashMap<>();
    private final Set<UUID> replay = new HashSet<>();
    private final BukkitTask clock;
    private long ticks;

    Actions(JustEconomyPlugin plugin) {
        this.plugin = plugin;
        clock = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks++;
            for (UUID id : List.copyOf(pending.keySet())) {
                Pending action = pending.get(id); Player player = action.player();
                if (player == null || !player.isOnline() || player.isDead()) { pending.remove(id); continue; }
                if (!action.valid().getAsBoolean()) { cancel(player); continue; }
                if (ticks >= action.due()) { pending.remove(id); action.run().run(); }
                else if (ticks % 20 == 0) player.sendActionBar(plugin.messages().component("countdown", "seconds", (action.due() - ticks + 19) / 20));
            }
        }, 1, 1);
    }

    boolean busy(Player player) { return pending.containsKey(player.getUniqueId()) || completing.contains(player.getUniqueId()); }
    private CommandRules.Policy permissions(Player player, CommandRules.Policy policy) {
        return new CommandRules.Policy(player.hasPermission("justeconomy.bypass.cost") ? 0 : policy.cost(), player.hasPermission("justeconomy.bypass.timer") ? 0 : policy.seconds());
    }

    void gate(Player player, CommandRules.Policy requested, BooleanSupplier valid, Runnable run) {
        gate(player, requested, valid, () -> true, run);
    }

    private void gate(Player player, CommandRules.Policy requested, BooleanSupplier valid, BooleanSupplier beforeExecution, Runnable run) {
        if (busy(player)) { plugin.messages().send(player, "pending"); return; }
        CommandRules.Policy policy = permissions(player, requested);
        try {
            if (!valid.getAsBoolean()) return;
            plugin.ensure(player);
            if (plugin.ledger().balance(player.getUniqueId()) < policy.cost()) { plugin.messages().send(player, "insufficient", "amount", plugin.money(policy.cost())); return; }
            Runnable execute = () -> {
                try {
                    if (!player.isOnline() || player.isDead() || !valid.getAsBoolean() || !beforeExecution.getAsBoolean() || !valid.getAsBoolean()) return;
                    if (policy.cost() > 0) plugin.ledger().withdraw(player.getUniqueId(), policy.cost());
                    run.run();
                    if (policy.cost() > 0) plugin.messages().send(player, "charged", "amount", plugin.money(policy.cost()));
                } catch (Exception e) { plugin.error(player, e); }
            };
            waitFor(player, policy.seconds(), valid, execute);
        } catch (Exception e) { plugin.error(player, e); }
    }

    private void waitFor(Player player, int seconds, BooleanSupplier valid, Runnable execute) {
        if (seconds == 0) execute.run();
        else {
            pending.put(player.getUniqueId(), new Pending(player, player.getLocation().clone(), valid, execute, ticks + seconds * 20L));
            plugin.messages().send(player, "warmup", "seconds", seconds);
        }
    }

    void teleport(Player player, CommandRules.Policy requested, BooleanSupplier valid, java.util.function.Supplier<Location> destination) {
        if (busy(player)) { plugin.messages().send(player, "pending"); return; }
        CommandRules.Policy policy = permissions(player, requested);
        try {
            plugin.ensure(player);
            if (plugin.ledger().balance(player.getUniqueId()) < policy.cost()) { plugin.messages().send(player, "insufficient", "amount", plugin.money(policy.cost())); return; }
            if (!valid.getAsBoolean()) return;
            waitFor(player, policy.seconds(), valid, () -> {
                String token = null;
                try {
                    if (!valid.getAsBoolean() || !player.isOnline() || player.isDead()) return;
                    Location location = destination.get();
                    if (location == null || location.getWorld() == null) { plugin.messages().send(player, "teleport-failed"); return; }
                    if (!plugin.settings().crossWorld() && !player.getWorld().equals(location.getWorld())) { plugin.messages().send(player, "cross-world"); return; }
                    token = plugin.ledger().reserve(player.getUniqueId(), policy.cost());
                    String reservation = token;
                    completing.add(player.getUniqueId());
                    completingChecks.put(player.getUniqueId(), valid);
                    TeleportSafety.teleport(plugin, player, location, valid).whenComplete((ok, failure) -> {
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            completing.remove(player.getUniqueId());
                            completingChecks.remove(player.getUniqueId());
                            try {
                                boolean success = failure == null && Boolean.TRUE.equals(ok);
                                plugin.ledger().finish(reservation, success);
                                plugin.messages().send(player, success ? "teleported" : "teleport-failed");
                                if (success && policy.cost() > 0) plugin.messages().send(player, "charged", "amount", plugin.money(policy.cost()));
                            } catch (Exception e) { plugin.error(player, e); }
                        });
                    });
                } catch (Exception e) {
                    completing.remove(player.getUniqueId());
                    completingChecks.remove(player.getUniqueId());
                    if (token != null) try { plugin.ledger().finish(token, false); } catch (Exception refund) { e.addSuppressed(refund); }
                    plugin.error(player, e);
                }
            });
        } catch (Exception e) { plugin.error(player, e); }
    }

    void cancel(Player player) {
        if (pending.remove(player.getUniqueId()) != null) plugin.messages().send(player, "cancelled");
    }
    void cancelAll() {
        for (Pending action : List.copyOf(pending.values())) cancel(action.player());
    }
    void close() { clock.cancel(); cancelAll(); }
    boolean completing() { return !completing.isEmpty(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (replay.contains(player.getUniqueId())) return;
        String line = event.getMessage().substring(1);
        org.bukkit.command.Command command = Bukkit.getCommandMap().getCommand(line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT));
        if (plugin.owns(command)) return;
        try {
            String expanded = CommandIdentity.expand(line);
            if (plugin.owns(Bukkit.getCommandMap().getCommand(expanded.split("\\s+", 2)[0].toLowerCase(Locale.ROOT)))) return;
            CommandRules.Policy rule = plugin.settings().rules().match(expanded, CommandIdentity::same);
            if (rule == null) return;
            event.setCancelled(true);
            if (command == null || !command.testPermissionSilent(player)) { plugin.messages().send(player, "no-permission"); return; }
            // The delayed command gets a fresh permission and cancellation check before any debit.
            String label = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            BooleanSupplier check = () -> command.testPermissionSilent(player) && CommandIdentity.unchanged(label, command);
            Bukkit.getScheduler().runTask(plugin, () -> {
                gate(player, rule, check, () -> recheck(player, line), () -> Bukkit.getCommandMap().dispatch(player, line));
            });
        } catch (Exception e) { event.setCancelled(true); plugin.error(player, e); }
    }

    private boolean recheck(Player player, String line) {
        PlayerCommandPreprocessEvent check = new PlayerCommandPreprocessEvent(player, "/" + line);
        replay.add(player.getUniqueId());
        try { Bukkit.getPluginManager().callEvent(check); }
        finally { replay.remove(player.getUniqueId()); }
        if (check.isCancelled()) return false;
        if (!check.getMessage().equals("/" + line)) { plugin.messages().send(player, "command-changed"); return false; }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        Pending action = pending.get(event.getPlayer().getUniqueId());
        if (action == null || !plugin.settings().cancelMovement()) return;
        Location to = event.getTo();
        if (!action.origin().getWorld().equals(to.getWorld()) || action.origin().distanceSquared(to) > Math.pow(plugin.settings().moveTolerance(), 2)) cancel(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) { if (plugin.settings().cancelDamage() && event.getEntity() instanceof Player player && event.getFinalDamage() > 0) cancel(player); }
    @EventHandler public void quit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { cancel(event.getEntity()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void movedByTeleport(PlayerTeleportEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void completingTeleport(PlayerTeleportEvent event) {
        BooleanSupplier valid = completingChecks.get(event.getPlayer().getUniqueId());
        if (valid == null) return;
        Location destination = event.getTo();
        if (!valid.getAsBoolean() || !destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)
                || !TeleportSafety.safe(destination) || (!plugin.settings().crossWorld() && !event.getFrom().getWorld().equals(destination.getWorld()))) event.setCancelled(true);
    }
}
