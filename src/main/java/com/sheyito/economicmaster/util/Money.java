package com.sheyito.economicmaster.util;

import com.sheyito.economicmaster.config.ConfigManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class Money {

    private Money() {
    }

    public static double round(double amount) {
        int decimals = ConfigManager.general().decimals;
        return BigDecimal.valueOf(amount).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    public static String format(double amount) {
        int decimals = ConfigManager.general().decimals;
        String currencyName = ConfigManager.general().currencyName;
        // Locale.US pinned deliberately: the grouping/decimal separators must stay consistent
        // regardless of the host server's default locale (a German-locale JVM would otherwise
        // print "1.234,50" instead of "1,234.50").
        // Suffixed (not prefixed): the currency is a named word ("Sheyicoins"), not a
        // single-character symbol, so "10.00 Sheyicoins" reads naturally where "Sheyicoins10.00"
        // would not.
        return String.format(Locale.US, "%,." + decimals + "f", round(amount)) + " " + currencyName;
    }
}
