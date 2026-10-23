package io.github.rokeydoess.justeconomy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path directory;
    @BeforeEach void defaults() throws Exception {
        for (String file : new String[]{"config.yml", "command-rules.yml", "messages.yml"})
            try (var input = getClass().getResourceAsStream("/" + file)) { Files.copy(input, directory.resolve(file)); }
    }
    @Test void defaultConfigurationLoads() throws Exception {
        Settings settings = Settings.load(directory.toFile());
        assertEquals(4, settings.money().precision()); assertFalse(settings.offlinePay()); assertEquals(0, settings.payRange());
        assertTrue(settings.commands().values().stream().allMatch(Boolean::booleanValue));
        assertTrue(settings.cancelDamage()); assertEquals(3, settings.tpa().seconds());
        new Messages(directory.resolve("messages.yml").toFile(), getClass().getResourceAsStream("/messages.yml"));
    }
    @Test void invalidValuesDoNotFallBackSilently() throws Exception {
        String config = Files.readString(directory.resolve("config.yml"));
        Files.writeString(directory.resolve("config.yml"), config.replace("allow-offline: false", "allow-offline: maybe"));
        assertThrows(IllegalArgumentException.class, () -> Settings.load(directory.toFile()));
        Files.writeString(directory.resolve("config.yml"), "economy: [unclosed");
        assertThrows(Exception.class, () -> Settings.load(directory.toFile()));
    }
    @Test void rulesValidatePrecisionAndTimerUnits() throws Exception {
        Files.writeString(directory.resolve("command-rules.yml"), "rules:\n  - command: home\n    cost: '0.0085'\n    standstill-seconds: 5\n");
        assertEquals(85, Settings.load(directory.toFile()).rules().match("home", String::equals).cost());
        Files.writeString(directory.resolve("command-rules.yml"), "rules:\n  - command: home\n    standstill-seconds: 1.5\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(directory.toFile()));
    }
    @Test void essentialsPreviewDetectsChangesAndInvalidRecords() throws Exception {
        Path source = directory.resolve("userdata"); Files.createDirectory(source);
        UUID valid = UUID.randomUUID(), invalid = UUID.randomUUID();
        Path account = source.resolve(valid + ".yml");
        Files.writeString(account, "last-account-name: Oak\nmoney: '12.0085'\n");
        Files.writeString(source.resolve(invalid + ".yml"), "money: '-1'\n");
        Money money = new Money(4, "$", "coin", "coins");
        EssentialsImport.Preview first = EssentialsImport.read(source, money, 1_000_000);
        assertEquals(1, first.rows().size()); assertEquals(120085, first.rows().getFirst().balance()); assertEquals(1, first.invalid());
        assertEquals(first.fingerprint(), EssentialsImport.read(source, money, 1_000_000).fingerprint());
        Files.writeString(account, "last-account-name: Oak\nmoney: '13'\n");
        assertNotEquals(first.fingerprint(), EssentialsImport.read(source, money, 1_000_000).fingerprint());
    }
}
