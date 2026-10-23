package io.github.rokeydoess.justeconomy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

public record Settings(Money money, long initial, long maximum, Map<String, Boolean> commands,
                       boolean offlinePay, double payRange, int pageSize, boolean crossWorld,
                       int expirySeconds, double moveTolerance, boolean cancelMovement, boolean cancelDamage,
                       CommandRules.Policy spawn, CommandRules.Policy tpa, CommandRules rules) {
    public static final Map<String, List<String>> COMMANDS;
    static {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        commands.put("balance", List.of("bal", "money")); commands.put("balancetop", List.of("baltop"));
        for (String name : List.of("pay", "spawn", "setspawn", "tpa", "tpaccept", "tpdeny", "tpacancel", "economy")) commands.put(name, List.of());
        COMMANDS = Collections.unmodifiableMap(commands);
    }

    public static YamlConfiguration yaml(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file); return yaml;
    }

    public static Settings load(File folder) throws Exception {
        YamlConfiguration c = yaml(new File(folder, "config.yml"));
        for (String section : List.of("economy", "commands", "payments", "leaderboard", "teleport", "teleport.spawn", "teleport.tpa", "standstill"))
            if (c.contains(section) && !c.isConfigurationSection(section)) throw new IllegalArgumentException(section + " must be a section");
        int precision = integer(c, "economy.precision", 4, 0, 8);
        Money money = new Money(precision, integer(c, "economy.display-precision", 2, 0, 8), text(c, "economy.symbol", "$"), text(c, "economy.singular", "coin"), text(c, "economy.plural", "coins"));
        long maximum = money.parse(text(c, "economy.maximum-balance", "1000000000"));
        long initial = money.parse(text(c, "economy.starting-balance", "0"));
        if (maximum <= 0 || maximum > 9_000_000_000_000_000L || initial > maximum) throw new IllegalArgumentException("Invalid maximum or starting balance");
        Map<String, Boolean> commands = new LinkedHashMap<>();
        for (String name : COMMANDS.keySet()) commands.put(name, bool(c, "commands." + name, true));
        double range = number(c, "payments.range", 0, 0, 30_000_000);
        YamlConfiguration r = yaml(new File(folder, "command-rules.yml"));
        if (!r.isList("rules")) throw new IllegalArgumentException("command-rules.yml: rules must be a list");
        List<CommandRules.Rule> rules = new ArrayList<>();
        for (Object entry : r.getList("rules")) {
            if (!(entry instanceof Map<?, ?> map)) throw new IllegalArgumentException("Each command rule must be a mapping");
            if (!(map.get("command") instanceof String command) || command.isBlank()) throw new IllegalArgumentException("Each rule needs a command");
            Object enabled = map.containsKey("enabled") ? map.get("enabled") : true;
            if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("Rule enabled must be true or false");
            if (!(Boolean) enabled) continue;
            Object cost = map.containsKey("cost") ? map.get("cost") : "0";
            long amount = money.parse(String.valueOf(cost));
            int seconds = strictSeconds(map.containsKey("standstill-seconds") ? map.get("standstill-seconds") : 0);
            if (amount > maximum) throw new IllegalArgumentException("Rule cost exceeds maximum balance: " + command);
            rules.add(new CommandRules.Rule(command, new CommandRules.Policy(amount, seconds)));
        }
        return new Settings(money, initial, maximum, Map.copyOf(commands), bool(c, "payments.allow-offline", false), range,
                integer(c, "leaderboard.page-size", 10, 1, 100), bool(c, "teleport.allow-cross-world", true),
                integer(c, "teleport.request-expiry-seconds", 60, 1, 3600), number(c, "standstill.movement-tolerance", 0.1, 0, 10),
                bool(c, "standstill.cancel-on-movement", true), bool(c, "standstill.cancel-on-damage", true),
                policy(c, "teleport.spawn", money, maximum), policy(c, "teleport.tpa", money, maximum), new CommandRules(rules));
    }

    private static CommandRules.Policy policy(ConfigurationSection c, String key, Money money, long maximum) {
        long cost = money.parse(text(c, key + ".cost", "0"));
        if (cost > maximum) throw new IllegalArgumentException(key + ".cost exceeds maximum balance");
        return new CommandRules.Policy(cost, integer(c, key + ".standstill-seconds", 3, 0, 3600));
    }

    private static int strictSeconds(Object value) {
        try {
            int result = new java.math.BigDecimal(String.valueOf(value)).intValueExact();
            if (result < 0 || result > 3600) throw new ArithmeticException();
            return result;
        } catch (RuntimeException e) { throw new IllegalArgumentException("standstill-seconds must be a whole number from 0 to 3600"); }
    }

    private static String text(ConfigurationSection c, String key, String fallback) {
        Object value = c.get(key, fallback);
        if (!(value instanceof String || value instanceof Number)) throw new IllegalArgumentException(key + " must be text or a number");
        return value.toString();
    }

    private static boolean bool(ConfigurationSection c, String key, boolean fallback) {
        Object value = c.get(key, fallback);
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException(key + " must be true or false");
        return b;
    }

    private static int integer(ConfigurationSection c, String key, int fallback, int min, int max) {
        double value = number(c, key, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(key + " must be a whole number");
        return (int) value;
    }

    private static double number(ConfigurationSection c, String key, double fallback, double min, double max) {
        Object value = c.get(key, fallback);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return n.doubleValue();
    }
}
