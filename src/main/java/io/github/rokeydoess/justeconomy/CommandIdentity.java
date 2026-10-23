package io.github.rokeydoess.justeconomy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.PluginIdentifiableCommand;
import java.util.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

final class CommandIdentity {
    static CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandIdentity() { }
    static boolean same(String a, String b) {
        Command first = Bukkit.getCommandMap().getCommand(a), second = Bukkit.getCommandMap().getCommand(b);
        if (first == null || second == null) return a.equalsIgnoreCase(b);
        if (first == second) return true;
        CommandNode<?> one = node(a), two = node(b);
        if (one == null || two == null) return false;
        if (one.getName().equals(two.getName())) return true;
        if (first instanceof PluginIdentifiableCommand owner && second instanceof PluginIdentifiableCommand other && owner.getPlugin() == other.getPlugin()) {
            String namespace = owner.getPlugin().getName().toLowerCase(Locale.ROOT) + ":";
            return withoutNamespace(one.getName(), namespace).equals(withoutNamespace(two.getName(), namespace));
        }
        return first.getClass() == second.getClass() && first.getPermission() != null && first.getPermission().startsWith("minecraft.command.")
                && first.getPermission().equals(second.getPermission())
                && withoutNamespace(one.getName(), "minecraft:").equals(withoutNamespace(two.getName(), "minecraft:"));
    }

    private static String withoutNamespace(String name, String namespace) { return name.startsWith(namespace) ? name.substring(namespace.length()) : name; }

    private static CommandNode<?> node(String label) {
        if (dispatcher == null) return null;
        CommandNode<?> node = dispatcher.getRoot().getChild(label.toLowerCase(Locale.ROOT));
        Set<CommandNode<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (node != null && node.getRedirect() != null && visited.add(node)) node = node.getRedirect();
        return node;
    }

    static boolean unchanged(String label, Command previous) {
        Command current = Bukkit.getCommandMap().getCommand(label.toLowerCase(Locale.ROOT));
        return current == previous || (current != null && current.getClass() == previous.getClass()
                && current.getName().equals(previous.getName()) && Objects.equals(current.getPermission(), previous.getPermission()));
    }

    static String expand(String input) {
        String line = input.strip().replaceFirst("^/", "");
        Set<String> visited = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            String[] parts = line.split("\\s+", 2);
            Command command = Bukkit.getCommandMap().getCommand(parts[0].toLowerCase(Locale.ROOT));
            if (!(command instanceof FormattedCommandAlias)) return line;
            if (!visited.add(command.getName())) throw new IllegalArgumentException("Recursive server alias; use the target command directly");
            String[] targets = Bukkit.getServer().getCommandAliases().get(command.getName());
            // Single forwarding aliases are safe to expand; scripts need their own explicit rule.
            if (targets == null || targets.length != 1) return line;
            String target = targets[0].strip();
            if (!target.endsWith("$1-") || target.substring(0, target.length() - 3).contains("$")) return line;
            line = target.substring(0, target.length() - 3) + (parts.length == 2 ? parts[1] : "");
        }
        throw new IllegalArgumentException("Server alias chain is too long; use the target command directly");
    }
}
