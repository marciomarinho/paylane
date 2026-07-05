package com.paylane.settlement.domain;

/**
 * The platform's take rate: 2.9% + 30c per captured payment, rounded to the nearest cent.
 * Integer minor units throughout — no floating-point money.
 */
public final class FeePolicy {
    private FeePolicy() {}

    private static final long FIXED_FEE_MINOR = 30;

    public static long feeFor(long amountMinor) {
        long variable = Math.round(amountMinor * 0.029);
        return variable + FIXED_FEE_MINOR;
    }
}
