package io.github.rokeydoess.justeconomy;

import org.bukkit.Location;

final class Payments {
    private Payments() { }
    static boolean inRange(Location from, Location to, double range) {
        if (range <= 0) return true;
        return from.getWorld() != null && from.getWorld().equals(to.getWorld()) && from.distanceSquared(to) <= range * range;
    }
}
