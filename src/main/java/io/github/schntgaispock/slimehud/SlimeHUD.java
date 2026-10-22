package io.github.schntgaispock.slimehud;

import io.github.schntgaispock.slimehud.command.CommandManager;
import io.github.schntgaispock.slimehud.placeholder.PlaceholderManager;
import io.github.schntgaispock.slimehud.translation.TranslationManager;
import io.github.schntgaispock.slimehud.util.PlayerDataStore;
import io.github.schntgaispock.slimehud.waila.HudController;
import io.github.schntgaispock.slimehud.waila.WAILAManager;
import java.util.logging.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class SlimeHUD extends JavaPlugin {

    private static SlimeHUD instance;

    private PlayerDataStore playerData;
    private HudController hudController;
    private TranslationManager translationManager;

    @Override
    public void onEnable() {
        instance = this;

        Plugin slimefun = getServer().getPluginManager().getPlugin("Slimefun");
        if (slimefun == null || !slimefun.isEnabled()) {
            getLogger().severe("Slimefun is unavailable or disabled. SF_SlimeHUD cannot start without its required dependency.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        getLogger().info("Loaded HUD settings from " + getDataFolder().toPath().resolve("config.yml").toAbsolutePath()
                + ": globalDisabled=" + getConfig().getBoolean("waila.disabled", false)
                + ", playerDefaultEnabled=" + getConfig().getBoolean("waila.default-enabled", false)
                + ", crouchOnly=" + getConfig().getBoolean("waila.crouch-only", true) + ".");
        if (getConfig().getBoolean("waila.disabled", false)) {
            getLogger().warning("waila.disabled: true blocks every player's /slimehud toggle. "
                    + "Use waila.disabled: false and waila.default-enabled: false for player opt-in.");
        }

        playerData = null;
        try {
            playerData = new PlayerDataStore(this);
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE,
                    "SlimeHUD player settings could not be loaded. Disabling to preserve player.yml.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        translationManager = new TranslationManager();
        hudController = new HudController();

        WAILAManager.setup();
        CommandManager.setup();
        PlaceholderManager.setup();

        getLogger().info("SF_SlimeHUD " + getPluginVersion() + " enabled with Slimefun Legacy and vanilla block HUD support.");
        getLogger().info("Paper/Purpur/Leaf/Folia compatibility target: Minecraft 1.21.11+.");
    }

    @Override
    public void onDisable() {
        WAILAManager.shutdown();
        if (playerData != null) {
            playerData.save();
            playerData = null;
        }
        instance = null;
    }

    public static SlimeHUD getInstance() {
        return instance;
    }

    public PlayerDataStore getPlayerData() {
        return playerData;
    }

    public static HudController getHudController() {
        return instance.hudController;
    }

    public static TranslationManager getTranslationManager() {
        return instance.translationManager;
    }

    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    public static NamespacedKey newNamespacedKey(String name) {
        return new NamespacedKey(instance, name);
    }

    public static void log(Level level, String... messages) {
        if (instance == null) {
            return;
        }
        for (String message : messages) {
            instance.getLogger().log(level, message);
        }
    }
}
