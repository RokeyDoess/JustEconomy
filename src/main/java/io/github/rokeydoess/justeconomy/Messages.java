package io.github.rokeydoess.justeconomy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Messages {
    private final Map<String, String> values;
    public Messages(File file, InputStream defaults) throws Exception {
        YamlConfiguration original = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(defaults), StandardCharsets.UTF_8)) { original.load(reader); }
        YamlConfiguration custom = Settings.yaml(file);
        Map<String, String> merged = new HashMap<>();
        for (String key : original.getKeys(false)) {
            Object value = custom.get(key, original.get(key));
            if (!(value instanceof String)) throw new IllegalArgumentException("messages.yml: " + key + " must be text");
            merged.put(key, (String) value);
        }
        values = Map.copyOf(merged);
    }

    public Component component(String key, Object... replacements) {
        String text = values.getOrDefault(key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    public void send(CommandSender to, String key, Object... replacements) {
        if (!values.getOrDefault(key, key).isEmpty()) to.sendMessage(component(key, replacements));
    }
}
