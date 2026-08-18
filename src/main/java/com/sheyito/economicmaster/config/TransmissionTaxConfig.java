package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/transmission_tax.json. Every taxed transaction
 * (see {@link com.sheyito.economicmaster.economy.EconomyManager#grossWithTax}/{@code
 * #netAfterTax}) is a "doble corte": the payer pays {@link #taxPercent} extra on top of the
 * sticker price, the receiver gets {@link #taxPercent} less - both halves are burned, never
 * credited to anyone.
 */
public class TransmissionTaxConfig {
    public boolean enabled = true;
    public double taxPercent = 0.10;

    public static TransmissionTaxConfig defaults() {
        return new TransmissionTaxConfig();
    }
}
