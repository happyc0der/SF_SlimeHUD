package io.github.schntgaispock.slimehud.util;

import io.github.schntgaispock.slimehud.SlimeHUD;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class PlayerDataStore {

    private final Logger logger;
    private final Path file;
    private final YamlConfiguration config;
    private boolean dirty;

    public PlayerDataStore(SlimeHUD plugin) {
        this(new File(plugin.getDataFolder(), "player.yml").toPath(), plugin.getLogger());
    }

    PlayerDataStore(Path file, Logger logger) {
        this.logger = logger;
        this.file = file;
        try {
            this.config = PlayerSettingsFile.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot load SlimeHUD player.yml; original player settings are retained", exception);
        }
        dirty = Files.notExists(file, LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public synchronized String getString(String path, String def) {
        return config.getString(path, def);
    }

    /** Distinguish a saved choice from the server's default without modifying player.yml. */
    public synchronized boolean hasPreference(String path) {
        return config.isSet(path);
    }

    public synchronized void set(String path, Object value) {
        if (!Objects.equals(config.get(path), value)) {
            config.set(path, value);
            dirty = true;
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            PlayerSettingsFile.save(config, file);
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.SEVERE,
                    "Could not save player.yml; previous file is retained and changes remain pending", exception);
        }
    }
}
