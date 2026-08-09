package com.sheyito.economicmaster.util;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

/** Backs the money display in every single command's chat output. */
class MoneyTest {

    @Test
    void roundsToConfiguredDecimals() {
        GeneralConfig config = new GeneralConfig();
        config.decimals = 2;
        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(config);
            assertEquals(10.13, Money.round(10.126));
            assertEquals(10.0, Money.round(9.999));
        }
    }

    @Test
    void formatsWithCurrencyNameAndDecimals() {
        GeneralConfig config = new GeneralConfig();
        config.decimals = 2;
        config.currencyName = "Sheyicoins";
        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(config);
            assertEquals("1,234.50 Sheyicoins", Money.format(1234.5));
        }
    }

    @Test
    void formatIsLocaleIndependent() {
        GeneralConfig config = new GeneralConfig();
        config.decimals = 0;
        config.currencyName = "Sheyicoins";
        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(config);
            assertEquals("10 Sheyicoins", Money.format(9.6));
        }
    }
}
