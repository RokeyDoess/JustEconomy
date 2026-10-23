package io.github.rokeydoess.justeconomy;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/** Child-process fixture that exits without shutdown hooks or connection cleanup. */
public final class CrashWriter {
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]); UUID id = UUID.fromString(args[1]);
        if (args[2].equals("committed")) {
            Ledger ledger = new Ledger(database, 4, 1_000_000);
            ledger.deposit(id, 10);
            ledger.reserve(id, 5);
        } else {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            connection.setAutoCommit(false);
            try (PreparedStatement s = connection.prepareStatement("UPDATE accounts SET balance=0 WHERE uuid=?")) { s.setString(1, id.toString()); s.executeUpdate(); }
        }
        Runtime.getRuntime().halt(0);
    }
}
