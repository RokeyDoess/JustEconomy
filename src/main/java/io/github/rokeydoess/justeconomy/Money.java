package io.github.rokeydoess.justeconomy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public record Money(int precision, String symbol, String singular, String plural) {
    public Money {
        if (precision < 0 || precision > 8) throw new IllegalArgumentException("economy.precision must be between 0 and 8");
    }

    public long parse(String value) {
        try {
            if (value == null || value.length() > 64) throw new IllegalArgumentException("Amount is too long");
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0) throw new IllegalArgumentException("Amounts cannot be negative");
            if (amount.compareTo(BigDecimal.valueOf(9_000_000_000_000_000L, precision)) > 0 || amount.stripTrailingZeros().scale() > precision)
                throw new IllegalArgumentException("Amount is too large or has more than " + precision + " decimal places");
            return amount.movePointRight(precision).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Amount is too large or has more than " + precision + " decimal places");
        }
    }

    public long fromVault(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Amount must be finite and nonnegative");
        try {
            long result = BigDecimal.valueOf(value).movePointRight(precision).setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (value > 0 && result == 0) throw new IllegalArgumentException("Amount is below the supported precision");
            return result;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount is too large");
        }
    }

    public double decimal(long units) { return BigDecimal.valueOf(units, precision).doubleValue(); }

    public String format(long units) {
        return display(BigDecimal.valueOf(units, precision));
    }

    public String display(BigDecimal amount) {
        String pattern = "#,##0" + (precision == 0 ? "" : "." + "0".repeat(Math.min(2, precision)) + "#".repeat(Math.max(0, precision - 2)));
        return symbol + new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US)).format(amount);
    }
}
