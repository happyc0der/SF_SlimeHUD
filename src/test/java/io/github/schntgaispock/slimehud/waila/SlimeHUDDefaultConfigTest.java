package io.github.schntgaispock.slimehud.waila;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SlimeHUDDefaultConfigTest {

    @Test
    void packagedDefaultsKeepNativeHudAvailableButRequireExplicitPlayerOptInAndSneaking() throws Exception {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(resource, "Missing packaged config.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resource, StandardCharsets.UTF_8));
            assertFalse(config.getBoolean("waila.disabled", true),
                    "Global disable must be false or /slimehud toggle is blocked");
            assertFalse(config.getBoolean("waila.default-enabled", true),
                    "Players without a saved preference must start with the HUD off");
            assertTrue(config.getBoolean("waila.crouch-only", false),
                    "New installs should only show the HUD while crouching");
        }
    }
}
