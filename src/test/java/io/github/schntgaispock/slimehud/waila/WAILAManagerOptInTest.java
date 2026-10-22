package io.github.schntgaispock.slimehud.waila;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.util.PlayerDataStore;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class WAILAManagerOptInTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-1111-2222-3333-444444444444");
    private static final String CHOICE = PLAYER_ID + ".waila";

    private MockedStatic<SlimeHUD> pluginAccess;
    private PlayerDataStore preferences;
    private YamlConfiguration config;

    @BeforeEach
    void setup() {
        WAILAManager.shutdown();
        SlimeHUD plugin = mock(SlimeHUD.class);
        preferences = mock(PlayerDataStore.class);
        config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getPlayerData()).thenReturn(preferences);
        pluginAccess = mockStatic(SlimeHUD.class);
        pluginAccess.when(SlimeHUD::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void teardown() {
        WAILAManager.shutdown();
        if (pluginAccess != null) {
            pluginAccess.close();
        }
    }

    @Test
    void missingSavedPreferenceDefaultsOffWithoutAllocatingController() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        assertFalse(WAILAManager.isEnabledForPlayer(PLAYER_ID));
        WAILAManager.getInstance().refreshPlayer(player);

        verify(preferences).getBoolean(CHOICE, false);
        assertNull(WAILAManager.getInstance().getWaila(player));
        verify(player, never()).getScheduler();
    }

    @Test
    void explicitSavedOptInOverridesDefaultOff() {
        when(preferences.getBoolean(CHOICE, false)).thenReturn(true);
        assertTrue(WAILAManager.isEnabledForPlayer(PLAYER_ID));
    }

    @Test
    void explicitSavedOptOutOverridesOptionalServerDefaultOn() {
        config.set("waila.default-enabled", true);
        when(preferences.getBoolean(CHOICE, true)).thenReturn(false);
        assertFalse(WAILAManager.isEnabledForPlayer(PLAYER_ID));
        verify(preferences).getBoolean(CHOICE, true);
    }
}
