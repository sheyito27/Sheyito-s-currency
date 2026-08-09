package com.sheyito.economicmaster.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Backs /bal level and the salary payout amount. Pure math, no Minecraft classes involved.
 */
class LevelCurveTest {

    @Test
    void fibonacciMatchesClassicSequence() {
        assertEquals(0, LevelCurve.fibonacci(0));
        assertEquals(1, LevelCurve.fibonacci(1));
        assertEquals(1, LevelCurve.fibonacci(2));
        assertEquals(2, LevelCurve.fibonacci(3));
        assertEquals(3, LevelCurve.fibonacci(4));
        assertEquals(5, LevelCurve.fibonacci(5));
        assertEquals(55, LevelCurve.fibonacci(10));
    }

    @Test
    void xpForLevelScalesByFibonacci() {
        assertEquals(10.0, LevelCurve.xpForLevel(1, 10.0));
        assertEquals(50.0, LevelCurve.xpForLevel(5, 10.0));
    }

    @Test
    void cumulativeXpSumsEachStep() {
        assertEquals(0.0, LevelCurve.cumulativeXpForLevel(0, 10.0));
        // fib(1)+fib(2)+fib(3) = 1+1+2 = 4, times baseXp 10 = 40
        assertEquals(40.0, LevelCurve.cumulativeXpForLevel(3, 10.0));
    }

    @Test
    void levelForXpStaysAtZeroBelowFirstThreshold() {
        assertEquals(0, LevelCurve.levelForXp(0.0, 20, 10.0));
        assertEquals(0, LevelCurve.levelForXp(9.0, 20, 10.0));
    }

    @Test
    void levelForXpAdvancesExactlyAtThreshold() {
        assertEquals(1, LevelCurve.levelForXp(10.0, 20, 10.0));
    }

    @Test
    void levelForXpNeverExceedsMaxLevel() {
        assertEquals(20, LevelCurve.levelForXp(1_000_000_000.0, 20, 10.0));
    }

    @Test
    void salaryForLevelInterpolatesLinearly() {
        assertEquals(10.0, LevelCurve.salaryForLevel(0, 10.0, 500.0, 20));
        assertEquals(500.0, LevelCurve.salaryForLevel(20, 10.0, 500.0, 20));
        assertEquals(255.0, LevelCurve.salaryForLevel(10, 10.0, 500.0, 20));
    }

    @Test
    void salaryForLevelClampsAboveMaxLevel() {
        assertEquals(500.0, LevelCurve.salaryForLevel(25, 10.0, 500.0, 20));
    }

    @Test
    void salaryForLevelAvoidsDivideByZeroWhenMaxLevelIsZero() {
        assertEquals(10.0, LevelCurve.salaryForLevel(0, 10.0, 500.0, 0));
    }
}
