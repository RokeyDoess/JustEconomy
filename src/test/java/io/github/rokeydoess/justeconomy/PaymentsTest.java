package io.github.rokeydoess.justeconomy;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class PaymentsTest {
    private World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (p, method, args) -> switch (method.getName()) {
            case "equals" -> p == args[0];
            case "hashCode" -> System.identityHashCode(p);
            default -> null;
        });
    }
    @Test void rangeIncludesHeightAndTheExactBoundaryButNeverAnotherWorld() {
        World world = world();
        Location from = new Location(world, 0, 0, 0);
        assertTrue(Payments.inRange(from, new Location(world, 3, 4, 0), 5));
        assertFalse(Payments.inRange(from, new Location(world, 3, 4.001, 0), 5));
        assertFalse(Payments.inRange(from, new Location(world(), 0, 0, 0), 5));
        assertTrue(Payments.inRange(from, new Location(world(), 0, 0, 0), 0));
    }
}
