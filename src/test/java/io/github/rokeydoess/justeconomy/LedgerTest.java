package io.github.rokeydoess.justeconomy;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("balances.db"); }
    private Ledger open() throws Exception { return new Ledger(database(), 4, 1_000_000); }

    @Test void transfersRollbackAndSurviveRestart() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (Ledger ledger = open()) {
            ledger.account(a, "Oak", 100); ledger.account(b, "Birch", 999_990);
            assertThrows(IllegalArgumentException.class, () -> ledger.transfer(a, b, 20));
            assertEquals(100, ledger.balance(a)); assertEquals(999_990, ledger.balance(b));
            ledger.transfer(a, b, 10);
            assertThrows(IllegalArgumentException.class, () -> ledger.transfer(a, b, 91));
            assertThrows(IllegalArgumentException.class, () -> ledger.transfer(a, a, 1));
            assertThrows(IllegalArgumentException.class, () -> ledger.deposit(a, -1));
        }
        try (Ledger ledger = open()) { assertEquals(90, ledger.balance(a)); assertEquals(1_000_000, ledger.balance(b)); }
    }

    @Test void concurrentTransfersKeepTheTotal() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (Ledger ledger = open(); ExecutorService pool = Executors.newFixedThreadPool(8)) {
            ledger.account(a, "Oak", 50_000); ledger.account(b, "Birch", 50_000);
            List<Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < 8; t++) { int side = t;
                work.add(() -> { for (int i = 0; i < 100; i++) ledger.transfer(side % 2 == 0 ? a : b, side % 2 == 0 ? b : a, 1); return null; });
            }
            for (Future<Void> future : pool.invokeAll(work)) future.get();
            assertEquals(50_000, ledger.balance(a)); assertEquals(50_000, ledger.balance(b));
        }
    }

    @Test void holdsAreIdempotentAndRecoverWithoutExceedingTheLimit() throws Exception {
        UUID id = UUID.randomUUID();
        try (Ledger ledger = open()) {
            ledger.account(id, "Oak", 1_000_000);
            String hold = ledger.reserve(id, 100);
            assertEquals(999_900, ledger.balance(id));
            assertThrows(IllegalArgumentException.class, () -> ledger.deposit(id, 1));
            ledger.finish(hold, false); ledger.finish(hold, false);
            assertEquals(1_000_000, ledger.balance(id));
            String paid = ledger.reserve(id, 50); ledger.finish(paid, true); ledger.finish(paid, false);
            assertEquals(999_950, ledger.balance(id));
            ledger.reserve(id, 100);
        }
        try (Ledger ledger = open()) { assertEquals(999_950, ledger.balance(id)); assertEquals(0, ledger.recoverHolds()); }
    }

    @Test void importsAreAtomicAndNeverOverwriteExistingAccounts() throws Exception {
        UUID existing = UUID.randomUUID(), fresh = UUID.randomUUID(), invalid = UUID.randomUUID();
        try (Ledger ledger = open()) {
            ledger.account(existing, "Oak", 25);
            List<Ledger.ImportRow> rows = List.of(new Ledger.ImportRow(existing, "Oak", 100), new Ledger.ImportRow(fresh, "Birch", 50));
            Ledger.ImportResult imported = ledger.importBalances(rows, "source");
            assertEquals(1, imported.imported()); assertEquals(1, imported.existing());
            assertEquals(25, ledger.balance(existing)); assertEquals(50, ledger.balance(fresh));
            assertEquals(0, ledger.importBalances(rows, "different-source").imported());
            UUID rollback = UUID.randomUUID();
            assertThrows(IllegalArgumentException.class, () -> ledger.importBalances(List.of(new Ledger.ImportRow(rollback, "Cedar", 10), new Ledger.ImportRow(invalid, "Pine", 1_000_001)), "bad"));
            assertFalse(ledger.exists(rollback)); assertFalse(ledger.imported(rollback));
        }
    }

    @Test void namesAreUpdatedWithoutChangingIdentityOrBalance() throws Exception {
        UUID id = UUID.randomUUID();
        try (Ledger ledger = open()) {
            ledger.account(id, "Oak", 10); ledger.account(id, "Birch", 500);
            assertEquals(10, ledger.balance(id)); assertEquals(id, ledger.find("bIrCh")); assertNull(ledger.find("Oak"));
            ledger.account(UUID.randomUUID(), "Birch", 0); assertNull(ledger.find("Birch"));
        }
    }

    @Test void precisionAndMaximumCannotInvalidateSavedBalances() throws Exception {
        UUID id = UUID.randomUUID();
        try (Ledger ledger = open()) { ledger.account(id, "Oak", 100); assertThrows(IllegalArgumentException.class, () -> ledger.maximum(99)); }
        assertThrows(SQLException.class, () -> new Ledger(database(), 2, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new Ledger(database(), 4, 99));
        try (Ledger ledger = open()) { assertEquals(100, ledger.balance(id)); }
    }

    @Test void backupIncludesCommittedWalAndPassesIntegrityCheck() throws Exception {
        UUID id = UUID.randomUUID(); Path backup = directory.resolve("backup.db");
        try (Ledger ledger = open()) {
            ledger.account(id, "Oak", 20); ledger.deposit(id, 10); ledger.backup(backup);
            assertThrows(IllegalArgumentException.class, () -> ledger.backup(backup));
        }
        try (Ledger copy = new Ledger(backup, 4, 1_000_000)) { assertEquals(30, copy.balance(id)); }
    }

    @Test void storageLockFailsClosedAndDoesNotAlterMoney() throws Exception {
        UUID id = UUID.randomUUID();
        try (Ledger ledger = open()) {
            ledger.account(id, "Oak", 20);
            try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + database()); Statement s = lock.createStatement()) {
                s.execute("BEGIN IMMEDIATE");
                assertThrows(SQLException.class, () -> ledger.deposit(id, 10));
                assertThrows(SQLException.class, () -> ledger.balance(id));
                s.execute("ROLLBACK");
            }
        }
        try (Ledger ledger = open()) { assertEquals(20, ledger.balance(id)); }
    }

    @Test void damagedStorageIsNotReplacedByAnEmptyLedger() throws Exception {
        Files.writeString(database(), "not a database");
        assertThrows(SQLException.class, this::open);
        assertEquals("not a database", Files.readString(database()));
    }

    @Test void leaderboardPaginatesSavedAccounts() throws Exception {
        try (Ledger ledger = open()) {
            for (int i = 0; i < 25; i++) ledger.account(UUID.randomUUID(), "Player" + i, i);
            assertEquals(24, ledger.top(1, 10).getFirst().balance());
            assertEquals(14, ledger.top(2, 10).getFirst().balance());
            assertEquals(5, ledger.top(3, 10).size()); assertTrue(ledger.top(4, 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> ledger.top(0, 10));
        }
    }

    @Test void abruptProcessExitKeepsCommittedMoneyAndRollsBackUncommittedWrites() throws Exception {
        UUID id = UUID.randomUUID();
        try (Ledger ledger = open()) { ledger.account(id, "Oak", 100); }
        crash(id, "committed");
        try (Ledger ledger = open()) { assertEquals(110, ledger.balance(id)); }
        crash(id, "uncommitted");
        try (Ledger ledger = open()) { assertEquals(110, ledger.balance(id)); }
    }

    private void crash(UUID id, String mode) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), database().toString(), id.toString(), mode)
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile()).start();
        if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroyForcibly(); fail("Crash fixture timed out"); }
        assertEquals(0, child.exitValue(), Files.readString(directory.resolve(mode + ".log")));
    }
}
