package io.github.rokeydoess.justeconomy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A single serialized connection keeps transfers and reservations in the same transaction. */
public final class Ledger implements AutoCloseable {
    public record Account(UUID id, String name, long balance) { }
    public record ImportRow(UUID id, String name, long balance) { }
    public record ImportResult(int imported, int existing) { }
    private final Connection db;
    private final Path file;
    private long maximum;

    public Ledger(Path file, int precision, long maximum) throws Exception {
        this.file = file.toAbsolutePath();
        this.maximum = maximum;
        Files.createDirectories(this.file.getParent());
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + this.file);
        try {
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=1000");
                try (ResultSet r = s.executeQuery("PRAGMA quick_check")) {
                    if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("Balance database failed its integrity check");
                }
                s.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL CHECK(balance >= 0))");
                s.execute("CREATE INDEX IF NOT EXISTS accounts_rank ON accounts(balance DESC, uuid)");
                s.execute("CREATE INDEX IF NOT EXISTS accounts_name ON accounts(name COLLATE NOCASE)");
                s.execute("CREATE TABLE IF NOT EXISTS holds (id TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES accounts(uuid), amount INTEGER NOT NULL CHECK(amount >= 0))");
                s.execute("CREATE TABLE IF NOT EXISTS imports (uuid TEXT PRIMARY KEY, source TEXT NOT NULL, amount INTEGER NOT NULL, imported_at TEXT NOT NULL)");
            }
            String stored = metadata("precision");
            if (stored != null && !stored.equals(Integer.toString(precision))) throw new SQLException("economy.precision differs from the saved database; restore the original setting");
            if (stored == null) try (PreparedStatement s = db.prepareStatement("INSERT INTO metadata VALUES ('precision', ?)")) {
                s.setString(1, Integer.toString(precision)); s.executeUpdate();
            }
            validateMaximum(maximum);
            recoverHolds();
        } catch (Exception e) { db.close(); throw e; }
    }

    private String metadata(String key) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
            s.setString(1, key); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    public synchronized void validateMaximum(long value) throws SQLException {
        if (value <= 0 || value > 9_000_000_000_000_000L) throw new IllegalArgumentException("Maximum balance is outside the supported range");
        try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM accounts a WHERE balance + COALESCE((SELECT SUM(amount) FROM holds h WHERE h.uuid=a.uuid),0)>? LIMIT 1")) {
            s.setLong(1, value); try (ResultSet r = s.executeQuery()) { if (r.next()) throw new IllegalArgumentException("Maximum balance is lower than an existing balance"); }
        }
    }

    public synchronized void maximum(long value) throws SQLException { validateMaximum(value); maximum = value; }

    public synchronized boolean exists(UUID id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM accounts WHERE uuid=?")) {
            s.setString(1, id.toString()); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    public synchronized void account(UUID id, String name, long initial) throws SQLException {
        check(initial);
        try (PreparedStatement s = db.prepareStatement("INSERT INTO accounts(uuid,name,balance) VALUES (?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name WHERE excluded.name<>'' AND excluded.name<>accounts.name")) {
            s.setString(1, id.toString()); s.setString(2, name == null ? "" : name); s.setLong(3, initial); s.executeUpdate();
        }
    }

    public synchronized UUID find(String name) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT uuid FROM accounts WHERE name=? COLLATE NOCASE LIMIT 2")) {
            s.setString(1, name); try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return null;
                UUID id = UUID.fromString(r.getString(1));
                return r.next() ? null : id;
            }
        }
    }

    public synchronized long balance(UUID id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
            s.setString(1, id.toString()); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }

    private long held(UUID id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT COALESCE(SUM(amount),0) FROM holds WHERE uuid=?")) {
            s.setString(1, id.toString()); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }

    private void check(long amount) {
        if (amount < 0 || amount > maximum) throw new IllegalArgumentException("Amount exceeds the configured balance limit");
    }

    private void write(UUID id, long amount) throws SQLException {
        check(amount);
        if (amount > maximum - held(id)) throw new IllegalArgumentException("Balance limit reached");
        try (PreparedStatement s = db.prepareStatement("UPDATE accounts SET balance=? WHERE uuid=?")) {
            s.setLong(1, amount); s.setString(2, id.toString());
            if (s.executeUpdate() != 1) throw new IllegalArgumentException("Unknown account");
        }
    }

    @FunctionalInterface private interface Transaction<T> { T run() throws SQLException; }
    private <T> T transaction(Transaction<T> work) throws SQLException {
        db.setAutoCommit(false);
        try { T result = work.run(); db.commit(); return result; }
        catch (SQLException e) {
            try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            try { db.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
        catch (RuntimeException e) { try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); db.close(); } throw e; }
        finally { if (!db.isClosed()) db.setAutoCommit(true); }
    }

    public synchronized long deposit(UUID id, long amount) throws SQLException {
        check(amount);
        return transaction(() -> { long next = Math.addExact(balance(id), amount); write(id, next); return next; });
    }

    public synchronized long withdraw(UUID id, long amount) throws SQLException {
        check(amount);
        return transaction(() -> {
            long next = balance(id) - amount;
            if (next < 0) throw new IllegalArgumentException("Insufficient funds");
            write(id, next); return next;
        });
    }

    public synchronized void set(UUID id, long amount) throws SQLException { check(amount); transaction(() -> { write(id, amount); return null; }); }

    public synchronized void transfer(UUID from, UUID to, long amount) throws SQLException {
        check(amount);
        if (from.equals(to) || amount == 0) throw new IllegalArgumentException("Choose another player and a positive amount");
        transaction(() -> {
            long remaining = balance(from) - amount;
            if (remaining < 0) throw new IllegalArgumentException("Insufficient funds");
            write(from, remaining);
            write(to, Math.addExact(balance(to), amount));
            return null;
        });
    }

    public synchronized String reserve(UUID id, long amount) throws SQLException {
        check(amount);
        return transaction(() -> {
            long remaining = balance(id) - amount;
            if (remaining < 0) throw new IllegalArgumentException("Insufficient funds");
            write(id, remaining);
            String token = UUID.randomUUID().toString();
            try (PreparedStatement s = db.prepareStatement("INSERT INTO holds VALUES (?,?,?)")) {
                s.setString(1, token); s.setString(2, id.toString()); s.setLong(3, amount); s.executeUpdate();
            }
            return token;
        });
    }

    public synchronized void finish(String token, boolean success) throws SQLException {
        transaction(() -> {
            UUID player = null; long amount = 0;
            try (PreparedStatement s = db.prepareStatement("SELECT uuid,amount FROM holds WHERE id=?")) {
                s.setString(1, token); try (ResultSet r = s.executeQuery()) { if (r.next()) { player = UUID.fromString(r.getString(1)); amount = r.getLong(2); } }
            }
            if (player == null) return null;
            try (PreparedStatement s = db.prepareStatement("DELETE FROM holds WHERE id=?")) { s.setString(1, token); s.executeUpdate(); }
            if (!success) write(player, Math.addExact(balance(player), amount));
            return null;
        });
    }

    public synchronized int recoverHolds() throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM holds")) { while (r.next()) tokens.add(r.getString(1)); }
        for (String token : tokens) finish(token, false);
        return tokens.size();
    }

    public synchronized List<Account> top(int page, int size) throws SQLException {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
        List<Account> result = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement("SELECT uuid,name,balance FROM accounts ORDER BY balance DESC,uuid LIMIT ? OFFSET ?")) {
            s.setInt(1, size); s.setLong(2, ((long) page - 1) * size);
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(new Account(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3))); }
        }
        return List.copyOf(result);
    }

    public synchronized boolean imported(UUID id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM imports WHERE uuid=?")) {
            s.setString(1, id.toString()); try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    public synchronized ImportResult importBalances(List<ImportRow> rows, String source) throws SQLException {
        return transaction(() -> {
            int imported = 0, existing = 0;
            for (ImportRow row : rows) {
                check(row.balance());
                if (imported(row.id()) || exists(row.id())) { existing++; continue; }
                account(row.id(), row.name(), row.balance());
                try (PreparedStatement s = db.prepareStatement("INSERT INTO imports VALUES (?,?,?,?)")) {
                    s.setString(1, row.id().toString()); s.setString(2, source); s.setLong(3, row.balance()); s.setString(4, Instant.now().toString()); s.executeUpdate();
                }
                imported++;
            }
            return new ImportResult(imported, existing);
        });
    }

    public synchronized Path backup(Path destination) throws Exception {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        if (Files.exists(destination)) throw new IllegalArgumentException("Backup file already exists");
        try (PreparedStatement s = db.prepareStatement("VACUUM INTO ?")) { s.setString(1, destination.toAbsolutePath().toString()); s.execute(); }
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + destination.toAbsolutePath()); Statement s = copy.createStatement(); ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
            if (!r.next() || !"ok".equals(r.getString(1))) throw new SQLException("Backup failed its integrity check");
        }
        return destination;
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
