package io.github.schntgaispock.slimehud.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.util.PlayerDataStore;
import io.github.schntgaispock.slimehud.waila.WAILAManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SlimeHUDToggleTest {
    private static final UUID UUID1 = UUID.fromString("00000000-1111-2222-3333-444444444444");

    @Test
    void freshPlayerCanEnableAndDisableHudWithoutGlobalDisable() {
        SlimeHUD plugin = mock(SlimeHUD.class);
        PlayerDataStore data = mock(PlayerDataStore.class);
        WAILAManager manager = mock(WAILAManager.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("waila.disabled", false);
        config.set("waila.default-enabled", false);
        when(plugin.getPlayerData()).thenReturn(data);
        when(plugin.getConfig()).thenReturn(config);
        when(player.hasPermission("slimehud.togglewaila")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID1);
        AtomicBoolean enabled = new AtomicBoolean(false);
        doAnswer(invocation -> {
            enabled.set((Boolean) invocation.getArgument(1));
            return null;
        }).when(data).set(UUID1 + ".waila", true);
        doAnswer(invocation -> {
            enabled.set((Boolean) invocation.getArgument(1));
            return null;
        }).when(data).set(UUID1 + ".waila", false);
        try (MockedStatic<SlimeHUD> pluginAccess = mockStatic(SlimeHUD.class);
             MockedStatic<WAILAManager> managerAccess = mockStatic(WAILAManager.class)) {
            pluginAccess.when(SlimeHUD::getInstance).thenReturn(plugin);
            managerAccess.when(() -> WAILAManager.isEnabledForPlayer(UUID1))
                    .thenAnswer(invocation -> enabled.get());
            managerAccess.when(WAILAManager::getInstance).thenReturn(manager);
            SlimeHUDCommandExecutor executor = new SlimeHUDCommandExecutor();
            assertTrue(executor.onCommand(player, command, "slimehud", new String[] {"toggle"}));
            assertTrue(enabled.get(), "First toggle must turn on a default-off player's HUD");
            assertTrue(executor.onCommand(player, command, "slimehud", new String[] {"toggle"}));
            assertFalse(enabled.get(), "Second toggle must turn the same player's HUD off");
            verify(data).set(UUID1 + ".waila", true);
            verify(data).set(UUID1 + ".waila", false);
            verify(data, times(2)).save();
            verify(manager, times(2)).refreshPlayer(player);
            verify(player, times(2)).sendMessage(anyString());
        }
    }

    @Test
    void globalDisablePreventsTogglingAndSavingPreference() {
        SlimeHUD plugin = mock(SlimeHUD.class);
        PlayerDataStore data = mock(PlayerDataStore.class);
        Player player = mock(Player.class);
        Command command = mock(Command.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("waila.disabled", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getPlayerData()).thenReturn(data);
        when(player.hasPermission("slimehud.togglewaila")).thenReturn(true);
        try (MockedStatic<SlimeHUD> pluginAccess = mockStatic(SlimeHUD.class)) {
            pluginAccess.when(SlimeHUD::getInstance).thenReturn(plugin);
            assertTrue(new SlimeHUDCommandExecutor().onCommand(
                    player, command, "slimehud", new String[] {"toggle"}));
            verify(data, never()).save();
            verify(player).sendMessage(anyString());
        }
    }
}
