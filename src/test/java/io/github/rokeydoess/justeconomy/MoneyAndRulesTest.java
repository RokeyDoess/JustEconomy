package io.github.rokeydoess.justeconomy;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MoneyAndRulesTest {
    @Test void fractionalMoneyRetainsJobRatesWithoutDrift() {
        Money money = new Money(4, "$", "coin", "coins");
        assertEquals(85, money.parse("0.0085")); assertEquals(0.0085, money.decimal(85));
        assertEquals(85, money.fromVault(0.0085)); assertEquals("$1,234.50", money.format(12_345_000));
        assertEquals("$0.01", money.format(85));
        assertThrows(IllegalArgumentException.class, () -> money.parse("0.00001"));
        assertThrows(IllegalArgumentException.class, () -> money.fromVault(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> money.fromVault(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> money.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> money.fromVault(0.000001));
        assertEquals(1, money.fromVault(0.00005));
        assertThrows(IllegalArgumentException.class, () -> money.parse("1e999999999"));
        assertThrows(IllegalArgumentException.class, () -> money.parse("1e-999999999"));
    }

    @Test void displayPrecisionIsIndependentOfAccounting() {
        Money money = new Money(4, "$", "coin", "coins");
        assertEquals("$0.00", money.format(1));
        assertEquals("$1.23", money.format(12_349));
        assertEquals("$1.24", money.format(12_350));
        assertEquals("$12.00", money.format(120_000));
        assertEquals("$0.00", money.display(new java.math.BigDecimal("-0.0001")));
        assertEquals(85, money.parse("0.0085"));
        assertEquals(0.0085, money.decimal(85));
        assertEquals("$0.0085", new Money(4, 4, "$", "coin", "coins").format(85));
        assertEquals("$13", new Money(4, 0, "$", "coin", "coins").format(125_000));
        assertEquals("$12.00", new Money(0, "$", "coin", "coins").format(12));
        assertThrows(IllegalArgumentException.class, () -> new Money(4, 9, "$", "coin", "coins"));
    }

    @Test void longestLiteralSubcommandWinsWithoutStacking() {
        CommandRules.Policy general = new CommandRules.Policy(100, 5), market = new CommandRules.Policy(200, 0);
        CommandRules rules = new CommandRules(List.of(new CommandRules.Rule("warp", general), new CommandRules.Rule("warp market", market)));
        assertEquals(market, rules.match("/WARP   Market player", String::equals));
        assertEquals(general, rules.match("warp marketplace", String::equals));
        assertNull(rules.match("warps market", String::equals));
        assertEquals(market, rules.match("plugin:warp market", (a, b) -> Set.of("warp", "plugin:warp").contains(a) && Set.of("warp", "plugin:warp").contains(b)));
        assertNull(rules.match("other:warp market", String::equals));
        assertThrows(IllegalArgumentException.class, () -> new CommandRules(List.of(new CommandRules.Rule("/warp", general), new CommandRules.Rule("WARP", market))));
    }
}
