package com.sheyito.economicmaster.util;

/**
 * Fibonacci-scaled leveling curve for the salary system: the XP needed to clear level L
 * (going from L-1 to L) is {@code baseXp * fibonacci(L)}. Because Fibonacci growth is
 * exponential (golden-ratio rate), the requirement for each new level explodes compared to
 * the last one, so climbing the first few levels is quick but reaching the top ones takes a
 * very long time even for very active players - that's deliberate, per how slow this is
 * meant to feel.
 */
public final class LevelCurve {

    private LevelCurve() {
    }

    public static long fibonacci(int n) {
        if (n <= 0) {
            return 0;
        }
        long a = 0;
        long b = 1;
        for (int i = 1; i < n; i++) {
            long next = a + b;
            a = b;
            b = next;
        }
        return b;
    }

    /** XP required to go from level-1 to level. */
    public static double xpForLevel(int level, double baseXp) {
        return baseXp * fibonacci(level);
    }

    /** Total cumulative XP required to reach {@code level} starting from level 0. */
    public static double cumulativeXpForLevel(int level, double baseXp) {
        double total = 0;
        for (int i = 1; i <= level; i++) {
            total += xpForLevel(i, baseXp);
        }
        return total;
    }

    public static int levelForXp(double xp, int maxLevel, double baseXp) {
        int level = 0;
        double remaining = xp;
        while (level < maxLevel) {
            double needed = xpForLevel(level + 1, baseXp);
            if (remaining < needed) {
                break;
            }
            remaining -= needed;
            level++;
        }
        return level;
    }

    /** Linear interpolation of the daily salary between level 0 (baseSalary) and maxLevel (maxSalary). */
    public static double salaryForLevel(int level, double baseSalary, double maxSalary, int maxLevel) {
        if (maxLevel <= 0) {
            return baseSalary;
        }
        double progress = Math.min(1.0, level / (double) maxLevel);
        return baseSalary + (maxSalary - baseSalary) * progress;
    }
}
