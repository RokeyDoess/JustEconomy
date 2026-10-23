package io.github.rokeydoess.justeconomy;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class EssentialsImport {
    record Preview(List<Ledger.ImportRow> rows, String fingerprint, int invalid) { }

    static Preview read(Path directory, Money money, long maximum) throws Exception {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Essentials/userdata was not found");
        List<Path> files;
        try (var paths = Files.list(directory)) { files = paths.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList(); }
        if (files.size() > 100_000) throw new IllegalArgumentException("Import exceeds 100,000 source files");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        List<Ledger.ImportRow> rows = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        int invalid = 0;
        for (Path path : files) {
            if (Files.size(path) > 1_048_576) throw new IllegalArgumentException("Essentials source file exceeds 1 MiB: " + path.getFileName());
            byte[] bytes = Files.readAllBytes(path);
            hash.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0); hash.update(bytes); hash.update((byte) 0);
            try {
                String filename = path.getFileName().toString();
                UUID id = UUID.fromString(filename.substring(0, filename.length() - 4));
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate source UUID: " + id);
                YamlConfiguration yaml = new YamlConfiguration(); yaml.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
                if (!yaml.contains("money")) continue;
                long balance = money.parse(String.valueOf(yaml.get("money")));
                if (balance > maximum) throw new IllegalArgumentException("Source balance exceeds maximum");
                String name = yaml.getString("last-account-name", "");
                if (!name.matches("[A-Za-z0-9_]{0,16}")) name = "";
                rows.add(new Ledger.ImportRow(id, name, balance));
            } catch (Exception e) { invalid++; }
        }
        return new Preview(List.copyOf(rows), HexFormat.of().formatHex(hash.digest()), invalid);
    }
}
