package io.github.rokeydoess.justeconomy;

import java.util.*;
import java.util.function.BiPredicate;

public final class CommandRules {
    public record Policy(long cost, int seconds) {
        public static final Policy FREE = new Policy(0, 0);
    }
    public record Rule(String command, Policy policy) { }
    private final List<Rule> rules;

    public CommandRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        Set<String> unique = new HashSet<>();
        for (Rule rule : rules) {
            String command = normalize(rule.command());
            if (command.isBlank() || !unique.add(command)) throw new IllegalArgumentException("Command rules must have unique, nonempty commands");
        }
    }

    public static String normalize(String command) {
        return command.strip().replaceFirst("^/", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public Policy match(String line, BiPredicate<String, String> sameCommand) {
        String[] input = normalize(line).split(" ");
        Policy result = null; int longest = 0;
        for (Rule rule : rules) {
            String[] pattern = normalize(rule.command()).split(" ");
            if (pattern.length > input.length || pattern.length <= longest || !sameCommand.test(pattern[0], input[0])) continue;
            boolean matches = true;
            for (int i = 1; i < pattern.length; i++) if (!pattern[i].equals(input[i])) { matches = false; break; }
            if (matches) { result = rule.policy(); longest = pattern.length; }
        }
        return result;
    }
}
