package com.banan.olispearlfix;

import com.banan.olispearlfix.persistence.BindingStore;
import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class OlisPearlFixPlugin extends JavaPlugin {
    private StasisController controller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            PluginSettings settings = PluginSettings.read(getConfig());
            if (!settings.enabled()) {
                getLogger().info("Stasis fix is disabled in config.yml");
                return;
            }
            RodIdentity rods = new RodIdentity(this);
            controller = new StasisController(this, settings,
                    new BindingStore(getDataFolder().toPath().resolve("bindings")), rods);
            controller.load();
            getServer().getPluginManager().registerEvents(new StasisListener(controller, rods), this);
            var command = Objects.requireNonNull(getCommand("stasis"));
            StasisCommand executor = new StasisCommand(controller, rods);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            Bukkit.getScheduler().runTaskTimer(this, controller::saveAll,
                    settings.saveInterval() * 20L, settings.saveInterval() * 20L);
            for (var world : Bukkit.getWorlds()) {
                controller.onWorldLoad(world);
            }
            getLogger().info("Loaded " + controller.registry().all().size() + " stasis bindings");
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Cannot start stasis fix; saved records have been left intact", exception);
            controller = null;
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (controller != null) {
            controller.shutdown();
        }
    }
}
