package com.banan.olispearlfix;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(boolean enabled, int ownerLimit, boolean persistent, int saveInterval,
                             boolean debug, int maxOperations, int timeoutTicks, int ticketTicks,
                             int pearlRadius) {
    public static PluginSettings read(FileConfiguration config) {
        return new PluginSettings(config.getBoolean("enabled", true),
                positive(config, "max-stasis-per-player", 8, 128),
                config.getBoolean("persistence-enabled", true),
                positive(config, "save-interval", 60, 86400), config.getBoolean("debug", false),
                positive(config, "max-concurrent-activations", 8, 32),
                positive(config, "activation-timeout-ticks", 200, 1200),
                positive(config, "activation-ticket-ticks", 100, 1200),
                positive(config, "pearl-search-radius", 8, 16));
    }

    private static int positive(FileConfiguration config, String key, int fallback, int maximum) {
        int value = config.getInt(key, fallback);
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(key + " must be between 1 and " + maximum);
        }
        return value;
    }
}
