package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/quests_rewards.json, consumed by
 * "/sheyitoscurrency reward <jugador>" - the command FTB Quests' "Command" reward type
 * is expected to call on quest completion. Every quest pays the same flat {@link #amount};
 * there is no per-quest configuration, so the exact same command reward can be pasted into
 * every quest. An optional second argument on the command itself can still override the
 * amount for a specific call, for the rare case a server owner wants that.
 */
public class QuestRewardsConfig {
    public double amount = 10.0;

    public static QuestRewardsConfig defaults() {
        return new QuestRewardsConfig();
    }
}
