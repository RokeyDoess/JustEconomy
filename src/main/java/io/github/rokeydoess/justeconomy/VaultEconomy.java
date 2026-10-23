package io.github.rokeydoess.justeconomy;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import java.util.*;

@SuppressWarnings("deprecation")
public final class VaultEconomy extends AbstractEconomy {
    private final JustEconomyPlugin plugin;
    public VaultEconomy(JustEconomyPlugin plugin) { this.plugin = plugin; }
    @Override public boolean isEnabled() { return plugin.isEnabled() && plugin.ledger() != null; }
    @Override public String getName() { return "JustEconomy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return plugin.settings().money().precision(); }
    @Override public String format(double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Amount must be finite");
        return plugin.settings().money().display(java.math.BigDecimal.valueOf(amount));
    }
    @Override public String currencyNamePlural() { return plugin.settings().money().plural(); }
    @Override public String currencyNameSingular() { return plugin.settings().money().singular(); }

    private UUID id(String name) {
        try { return plugin.resolve(name); }
        catch (Exception e) { plugin.storageError(e); throw new IllegalStateException("Economy storage unavailable", e); }
    }

    @Override public boolean hasAccount(OfflinePlayer player) {
        try { return player != null && plugin.ledger().exists(player.getUniqueId()); }
        catch (Exception e) { plugin.storageError(e); throw new IllegalStateException("Economy storage unavailable", e); }
    }
    @Override public boolean hasAccount(String name) { UUID id = id(name); return id != null && hasAccount(plugin.getServer().getOfflinePlayer(id)); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return hasAccount(player); }

    @Override public double getBalance(OfflinePlayer player) {
        try { return player == null ? 0 : plugin.settings().money().decimal(plugin.ledger().balance(player.getUniqueId())); }
        catch (Exception e) { plugin.storageError(e); throw new IllegalStateException("Economy storage unavailable", e); }
    }
    @Override public double getBalance(String name) { UUID id = id(name); return id == null ? 0 : getBalance(plugin.getServer().getOfflinePlayer(id)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(OfflinePlayer player, double amount) {
        try { return player != null && plugin.ledger().balance(player.getUniqueId()) >= plugin.settings().money().fromVault(amount); }
        catch (IllegalArgumentException e) { return false; }
        catch (Exception e) { plugin.storageError(e); throw new IllegalStateException("Economy storage unavailable", e); }
    }
    @Override public boolean has(String name, double amount) { UUID id = id(name); return id != null && has(plugin.getServer().getOfflinePlayer(id), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    private EconomyResponse change(OfflinePlayer player, double amount, boolean deposit) {
        Money money = plugin.settings().money();
        if (player == null || !isEnabled()) return failure("Economy or account unavailable");
        try {
            long units = money.fromVault(amount);
            if (!plugin.ledger().exists(player.getUniqueId())) plugin.ensure(player);
            long balance = deposit ? plugin.ledger().deposit(player.getUniqueId(), units) : plugin.ledger().withdraw(player.getUniqueId(), units);
            return new EconomyResponse(money.decimal(units), money.decimal(balance), EconomyResponse.ResponseType.SUCCESS, "");
        } catch (IllegalArgumentException e) {
            double balance;
            try { balance = money.decimal(plugin.ledger().balance(player.getUniqueId())); }
            catch (Exception unavailable) { plugin.storageError(unavailable); return failure("Economy storage unavailable"); }
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, e.getMessage());
        }
        catch (Exception e) { plugin.storageError(e); return failure("Economy storage unavailable"); }
    }

    private EconomyResponse named(String name, double amount, boolean deposit) {
        try { UUID id = id(name); return id == null ? failure("Unknown player") : change(plugin.getServer().getOfflinePlayer(id), amount, deposit); }
        catch (Exception e) { return failure("Economy storage unavailable"); }
    }

    private EconomyResponse failure(String reason) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, reason); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return change(player, amount, false); }
    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return named(name, amount, false); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return change(player, amount, true); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return named(name, amount, true); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }

    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || !isEnabled()) return false;
        try { plugin.ensure(player); return true; }
        catch (Exception e) { plugin.storageError(e); return false; }
    }
    @Override public boolean createPlayerAccount(String name) {
        UUID id = id(name); return id != null && createPlayerAccount(plugin.getServer().getOfflinePlayer(id));
    }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return createPlayerAccount(player); }

    private EconomyResponse unsupported() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported"); }
    @Override public List<String> getBanks() { return List.of(); }
    @Override public EconomyResponse createBank(String bank, String owner) { return unsupported(); }
    @Override public EconomyResponse createBank(String bank, OfflinePlayer owner) { return unsupported(); }
    @Override public EconomyResponse deleteBank(String bank) { return unsupported(); }
    @Override public EconomyResponse bankBalance(String bank) { return unsupported(); }
    @Override public EconomyResponse bankHas(String bank, double amount) { return unsupported(); }
    @Override public EconomyResponse bankWithdraw(String bank, double amount) { return unsupported(); }
    @Override public EconomyResponse bankDeposit(String bank, double amount) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String bank, String player) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String bank, OfflinePlayer player) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String bank, String player) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String bank, OfflinePlayer player) { return unsupported(); }
}
