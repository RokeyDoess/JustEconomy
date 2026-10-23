package io.github.rokeydoess.justeconomy;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

final class TeleportSafety {
    private static final Set<Material> HAZARDS = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE);
    private TeleportSafety() { }

    static CompletableFuture<Boolean> teleport(JustEconomyPlugin plugin, Player player, Location location, BooleanSupplier valid) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ()) || !location.getWorld().getWorldBorder().isInside(location)) return CompletableFuture.completedFuture(false);
        location.getWorld().getChunkAtAsync(location).whenComplete((chunk, failure) -> {
            if (failure != null || !plugin.isEnabled()) { result.complete(false); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!player.isOnline() || player.isDead() || !valid.getAsBoolean() || !safe(location)) { result.complete(false); return; }
                    player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> {
                        if (error != null) result.completeExceptionally(error); else result.complete(Boolean.TRUE.equals(ok));
                    });
                } catch (Exception e) { result.completeExceptionally(e); }
            });
        });
        return result;
    }

    static boolean safe(Location location) {
        World world = location.getWorld(); int y = location.getBlockY();
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight() || !world.getWorldBorder().isInside(location)) return false;
        Block feet = location.getBlock(), head = feet.getRelative(0, 1, 0), below = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && below.getType().isSolid()
                && !HAZARDS.contains(feet.getType()) && !HAZARDS.contains(head.getType()) && !HAZARDS.contains(below.getType());
    }
}
