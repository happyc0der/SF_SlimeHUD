package io.github.schntgaispock.slimehud.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.translation.TranslationManager;
import io.github.schntgaispock.slimehud.util.PlayerDataStore;
import io.github.schntgaispock.slimehud.waila.HudController;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Exercises production ownership and fallback code through reflected call
 * boundaries. The peer below supplies controlled API return values and records
 * side effects; actual WIT plugin linkage/rendering is covered by native tests.
 */
class WITIntegrationTest {

    private SlimeHUD plugin;
    private Player player;
    private WITIntegration integration;

    @BeforeEach
    void setup() throws Exception {
        Peer.reset();
        plugin = mock(SlimeHUD.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("WITIntegrationTest"));
        PlayerDataStore playerData = mock(PlayerDataStore.class);
        when(playerData.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        when(plugin.getPlayerData()).thenReturn(playerData);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        when(player.getName()).thenReturn("WitTestPlayer");
        when(player.getWorld()).thenReturn(mock(World.class));

        Constructor<WITIntegration> constructor = WITIntegration.class.getDeclaredConstructor(SlimeHUD.class);
        constructor.setAccessible(true);
        integration = constructor.newInstance(plugin);
        set("instance", integration);
        set("hooked", true);
        Plugin wit = mock(Plugin.class);
        when(wit.isEnabled()).thenReturn(true);
        set("witPlugin", wit);
        set("witGetSettings", Peer.class.getMethod("getSettings", Player.class));
        set("witIsDisabledWorld", Peer.class.getMethod("isDisabledWorld", World.class));
        set("witIsHidden", Peer.class.getMethod("isHidden"));
        set("witGetLookingAt", Peer.class.getMethod("getLookingAt", Player.class));
        set("witSetLookingAt", Peer.class.getMethod("setLookingAt", Player.class, Info.class));
        set("witGetDisplays", Peer.class.getMethod("getDisplays"));
        set("witRemoveBar", Peer.class.getMethod("removeBar", Player.class, String.class));
        set("settingsDisabled", Settings.class.getField("disabled"));
        set("settingsType", Settings.class.getField("type"));
        set("witSneakingMode", Peer.class.getField("SNEAKING_MODE"));
        set("witBlocksEnabled", Peer.class.getField("BLOCKS_ENABLED"));
        set("witIsAllowedBlock", Peer.class.getMethod("isAllowedBlock", Material.class));
        set("infoConstructor", Info.class.getConstructor());
        set("infoSetName", Info.class.getMethod("setName", Component.class));
        set("infoAddSuffix", Info.class.getMethod("addSuffix", Component.class));
        set("apiUpdateBar", Peer.class.getMethod("updateBar", Info.class, Player.class));
    }

    @AfterEach
    void cleanup() throws Exception {
        set("instance", null);
    }

    @Test
    void statusQueryDoesNotAcquireOrRemoveDisplays() throws Exception {
        assertTrue(WITIntegration.shouldDelegate(player));
        assertTrue(owners().isEmpty());
        Peer.hidden = true;
        assertFalse(WITIntegration.shouldDelegate(player));
        assertTrue(Peer.removed.isEmpty());
    }

    @Test
    void standingAfterSneakingRemovesWitBarExactlyOnce() {
        Peer.SNEAKING_MODE = true;
        when(player.isSneaking()).thenReturn(true);
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.lookingAt = new Info();

        when(player.isSneaking()).thenReturn(false);
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar"), Peer.removed);
        assertNull(Peer.lookingAt);
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar"), Peer.removed);

        when(player.isSneaking()).thenReturn(true);
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        when(player.isSneaking()).thenReturn(false);
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar", "bossbar"), Peer.removed);
    }

    @Test
    void invalidRendererFallsBackAndRemovesPreviouslyOwnedRenderer() {
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.settings.type = "does-not-exist";
        assertFalse(WITIntegration.shouldDelegate(player));
        assertTrue(Peer.removed.isEmpty());
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar"), Peer.removed);
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(1, Peer.removed.size());

        Peer.settings.type = "actionbar";
        assertTrue(WITIntegration.updateDisplayOwnership(player));
    }

    @Test
    void absentOrNullRendererDoesNotSuppressNativeHud() {
        Peer.displays.clear();
        assertFalse(WITIntegration.shouldDelegate(player));
        Peer.displays.put("bossbar", null);
        assertFalse(WITIntegration.shouldDelegate(player));
        Peer.settings.type = null;
        assertFalse(WITIntegration.shouldDelegate(player));
    }

    @Test
    void hiddenWitHandsOffAnActionBarWithoutRepeatedBlankPackets() {
        Peer.settings.type = "actionbar";
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.hidden = true;
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("actionbar"), Peer.removed);
    }

    @Test
    void playerAndWorldDisablesEachHandOffToNative() {
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.settings.disabled = true;
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar"), Peer.removed);
        Peer.settings.disabled = false;
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.worldDisabled = true;
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar", "bossbar"), Peer.removed);
    }

    @Test
    void displayTypeChangeRemovesOldTypeAndPreservesCurrentTarget() {
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        Peer.lookingAt = new Info();
        Peer.settings.type = "actionbar";
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        assertEquals(List.of("bossbar"), Peer.removed);
        assertNotNull(Peer.lookingAt);
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        assertEquals(1, Peer.removed.size());
    }

    @Test
    void ownedRendererFailureIsConsumedAndNativeFallbackIsNotRepeatedlyCleared() throws Exception {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        when(block.getType()).thenReturn(Material.STONE);
        SlimefunItem item = mock(SlimefunItem.class);
        TranslationManager translations = mock(TranslationManager.class);
        when(translations.getItemName(item)).thenReturn("&aTest Machine");
        HudController controller = mock(HudController.class);
        when(controller.processRequest(any())).thenReturn("Power: 12 J");

        try (MockedStatic<BlockStorage> storage = mockStatic(BlockStorage.class);
                MockedStatic<SlimeHUD> slimehud = mockStatic(SlimeHUD.class)) {
            storage.when(() -> BlockStorage.check(block)).thenReturn(item);
            slimehud.when(SlimeHUD::getTranslationManager).thenReturn(translations);
            slimehud.when(SlimeHUD::getHudController).thenReturn(controller);
            Peer.settings.type = "actionbar";
            assertTrue(WITIntegration.updateDisplayOwnership(player));
            Peer.failRenderer = true;

            assertTrue(handle(block));
            assertTrue(WITIntegration.hasBridgeFailure(player));
            assertNull(Peer.lookingAt);
            assertEquals(List.of("actionbar"), Peer.removed);
            assertFalse(WITIntegration.updateDisplayOwnership(player));
            assertTrue(handle(block));
            assertNull(Peer.lookingAt);
            assertFalse(WITIntegration.updateDisplayOwnership(player));
            assertEquals(List.of("actionbar"), Peer.removed);

            Peer.failRenderer = false;
            assertTrue(handle(block));
            assertFalse(WITIntegration.hasBridgeFailure(player));
            assertTrue(WITIntegration.updateDisplayOwnership(player));
            assertNotNull(Peer.lookingAt);
            assertEquals(3, Peer.updateCalls);
        }
    }

    @Test
    void anotherWitProviderCanRecoverAfterBridgeFailure() throws Exception {
        failures().add(player.getUniqueId());
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        Peer.lookingAt = new Info();
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        assertFalse(WITIntegration.hasBridgeFailure(player));
    }

    @Test
    void targetAloneCannotOverrideInvalidRendererOrSneakMode() throws Exception {
        failures().add(player.getUniqueId());
        Peer.lookingAt = new Info();
        Peer.settings.type = "invalid";
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertTrue(WITIntegration.hasBridgeFailure(player));
        Peer.settings.type = "bossbar";
        Peer.SNEAKING_MODE = true;
        assertFalse(WITIntegration.updateDisplayOwnership(player));
        assertTrue(WITIntegration.hasBridgeFailure(player));
    }

    @Test
    void unclaimedBlockLookupFailureLeavesOtherWitHandlersAvailable() throws Exception {
        Block block = mock(Block.class);
        try (MockedStatic<BlockStorage> storage = mockStatic(BlockStorage.class)) {
            storage.when(() -> BlockStorage.check(block)).thenThrow(new IllegalStateException("lookup failed"));
            assertFalse(handle(block));
            assertFalse(WITIntegration.hasBridgeFailure(player));
            assertTrue(WITIntegration.shouldDelegate(player));
            assertTrue(Peer.removed.isEmpty());
        }
    }

    @Test
    void quitClearsFailureWarningAndOwnershipWithoutSendingDisplayPackets() throws Exception {
        assertTrue(WITIntegration.updateDisplayOwnership(player));
        failures().add(player.getUniqueId());
        warnings().add(player.getUniqueId());
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        integration.onPlayerQuit(event);
        assertFalse(WITIntegration.hasBridgeFailure(player));
        assertTrue(warnings().isEmpty());
        assertTrue(owners().isEmpty());
        assertTrue(Peer.removed.isEmpty());
    }

    @Test
    void partiallyRegisteredHookIsRemovedEvenBeforeHookedBecomesTrue() throws Exception {
        BiFunction<Block, Player, Boolean> handler = (block, viewer) -> false;
        Object addon = new Object();
        set("hooked", false);
        set("blockHandler", handler);
        set("witAddonProxy", addon);
        set("apiRemoveBlockHandler", Peer.class.getMethod("removeBlockHandler", BiFunction.class));
        set("apiUnregisterAddon", Peer.class.getMethod("unregisterAddon", Object.class));
        invoke("unhook");
        assertSame(handler, Peer.removedHandler);
        assertSame(addon, Peer.removedAddon);
        assertNull(get("blockHandler"));
        assertNull(get("witAddonProxy"));
        assertFalse(WITIntegration.isHooked());
    }

    @Test
    void deferredReloadCleanupCannotEraseNewlyAcquiredWitDisplay() throws Exception {
        List<Consumer<ScheduledTask>> callbacks = capturePlayerTasks();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            Peer.settings.type = "actionbar";
            assertTrue(WITIntegration.updateDisplayOwnership(player));
            invoke("releaseTrackedDisplays");
            assertEquals(1, callbacks.size());

            assertTrue(WITIntegration.updateDisplayOwnership(player));
            Peer.lookingAt = new Info();
            callbacks.getFirst().accept(null);
            assertTrue(Peer.removed.isEmpty());
            assertNotNull(Peer.lookingAt);
            assertTrue(WITIntegration.isUsingActionBar(player));
        }
    }

    @Test
    void deferredReloadCleanupCannotEraseNativeDisplayAfterHandoff() throws Exception {
        List<Consumer<ScheduledTask>> callbacks = capturePlayerTasks();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            Peer.settings.type = "actionbar";
            assertTrue(WITIntegration.updateDisplayOwnership(player));
            invoke("releaseTrackedDisplays");
            Peer.hidden = true;
            assertFalse(WITIntegration.updateDisplayOwnership(player));
            assertEquals(List.of("actionbar"), Peer.removed);

            callbacks.getFirst().accept(null);
            assertEquals(List.of("actionbar"), Peer.removed);
            assertFalse(WITIntegration.isUsingActionBar(player));
        }
    }

    @Test
    void failedRehookKeepsPendingCleanupAvailableToNativeHandoff() throws Exception {
        List<Consumer<ScheduledTask>> callbacks = capturePlayerTasks();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            assertTrue(WITIntegration.updateDisplayOwnership(player));
            invoke("releaseTrackedDisplays");
            set("witPlugin", null);
            invoke("unhook");

            assertFalse(WITIntegration.updateDisplayOwnership(player));
            assertEquals(List.of("bossbar"), Peer.removed);
            callbacks.getFirst().accept(null);
            assertEquals(List.of("bossbar"), Peer.removed);
        }
    }

    private List<Consumer<ScheduledTask>> capturePlayerTasks() {
        when(plugin.isEnabled()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        List<Consumer<ScheduledTask>> callbacks = new ArrayList<>();
        when(scheduler.run(eq(plugin), any(), isNull())).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        });
        return callbacks;
    }

    private boolean handle(Block block) throws Exception {
        Method method = WITIntegration.class.getDeclaredMethod("handleBlock", Block.class, Player.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(integration, block, player);
    }

    private void invoke(String name) throws Exception {
        Method method = WITIntegration.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(integration);
    }

    private void set(String name, Object value) throws Exception {
        Field field = WITIntegration.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(integration, value);
    }

    private Object get(String name) throws Exception {
        Field field = WITIntegration.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(integration);
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> failures() throws Exception {
        return (Set<UUID>) get("bridgeFailures");
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> warnings() throws Exception {
        return (Set<UUID>) get("warnedBridgeFailures");
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, String> owners() throws Exception {
        return (Map<UUID, String>) get("delegatedDisplays");
    }

    public static final class Settings {
        public String type = "bossbar";
        public boolean disabled;
    }

    public static final class Info {
        public Info() {}
        public void setName(Component name) {}
        public void addSuffix(Component suffix) {}
    }

    public static final class Peer {
        public static boolean SNEAKING_MODE;
        public static boolean BLOCKS_ENABLED;
        static boolean hidden;
        static boolean worldDisabled;
        static boolean failRenderer;
        static int updateCalls;
        static Settings settings;
        static Map<String, Object> displays;
        static Info lookingAt;
        static List<String> removed;
        static Object removedHandler;
        static Object removedAddon;

        static void reset() {
            SNEAKING_MODE = false;
            BLOCKS_ENABLED = true;
            hidden = false;
            worldDisabled = false;
            failRenderer = false;
            updateCalls = 0;
            settings = new Settings();
            displays = new HashMap<>(Map.of("bossbar", new Object(), "actionbar", new Object()));
            lookingAt = null;
            removed = new ArrayList<>();
            removedHandler = null;
            removedAddon = null;
        }

        public static Settings getSettings(Player player) { return settings; }
        public static boolean isDisabledWorld(World world) { return worldDisabled; }
        public static boolean isHidden() { return hidden; }
        public static Info getLookingAt(Player player) { return lookingAt; }
        public static void setLookingAt(Player player, Info info) { lookingAt = info; }
        public static Map<String, Object> getDisplays() { return displays; }
        public static boolean isAllowedBlock(Material type) { return true; }
        public static void removeBar(Player player, String type) { removed.add(type); }
        public static void removeBlockHandler(BiFunction<Block, Player, Boolean> handler) { removedHandler = handler; }
        public static void unregisterAddon(Object addon) { removedAddon = addon; }

        public static void updateBar(Info info, Player player) {
            updateCalls++;
            lookingAt = info;
            if (failRenderer) {
                throw new IllegalStateException("renderer failed");
            }
        }
    }
}
