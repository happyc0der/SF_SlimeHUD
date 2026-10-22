package io.github.schntgaispock.slimehud.waila;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import sun.misc.Unsafe;

/** Exercises production failure handling; native ray and client rendering tests run separately. */
class PlayerWailaFailureTest {
    private Server previousServer;
    private PlayerWAILA hud;
    private final AtomicBoolean visible = new AtomicBoolean(true);
    private final AtomicBoolean sneaking = new AtomicBoolean(false);
    private final AtomicInteger actionBarClears = new AtomicInteger();
    private final YamlConfiguration config = new YamlConfiguration();
    private MockedStatic<SlimeHUD> pluginAccess;

    @BeforeEach
    void prepareProductionControllerWithImmediateSchedulers() throws Exception {
        previousServer = Bukkit.getServer();
        RegionScheduler regions = proxy(RegionScheduler.class, (object, method, arguments) -> {
            if (method.getName().equals("execute")) {
                ((Runnable) arguments[arguments.length - 1]).run();
                return null;
            }
            throw new AssertionError("Unexpected region call: " + method);
        });
        setField(Bukkit.class, null, "server", proxy(Server.class, (object, method, arguments) -> {
            if (method.getName().equals("getRegionScheduler")) {
                return regions;
            }
            if (method.getName().equals("isOwnedByCurrentRegion")) {
                return false;
            }
            throw new AssertionError("Unexpected server call: " + method);
        }));

        EntityScheduler entities = immediateEntityScheduler();
        Player.Spigot messages = new Player.Spigot() {
            @Override
            public void sendMessage(ChatMessageType position, BaseComponent... message) {
                assertEquals(ChatMessageType.ACTION_BAR, position);
                actionBarClears.incrementAndGet();
            }
        };
        Player player = proxy(Player.class, (object, method, arguments) -> switch (method.getName()) {
            case "isOnline", "isValid" -> true;
            case "isSneaking" -> sneaking.get();
            case "getScheduler" -> entities;
            case "spigot" -> messages;
            default -> throw new AssertionError("Unexpected player call: " + method);
        });
        BossBar bar = proxy(BossBar.class, (object, method, arguments) -> {
            if (method.getName().equals("setVisible")) {
                visible.set((boolean) arguments[0]);
                return null;
            }
            throw new AssertionError("Unexpected bar call: " + method);
        });

        // The real constructor registers a connected player's BossBar. These
        // failure-path tests replace only that transport/lifecycle boundary.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        hud = (PlayerWAILA) ((Unsafe) unsafeField.get(null)).allocateInstance(PlayerWAILA.class);
        setField(PlayerWAILA.class, hud, "player", player);
        setField(PlayerWAILA.class, hud, "bossBar", bar);
        setField(PlayerWAILA.class, hud, "requestSequence", new AtomicLong(7));
        setField(PlayerWAILA.class, hud, "displayMode", DisplayMode.BOSSBAR);
        setField(PlayerWAILA.class, hud, "displayVisible", true);
        setField(PlayerWAILA.class, hud, "facing", "Previous block | Previous details");
        setField(PlayerWAILA.class, hud, "facingBlock", "Previous block");
        setField(PlayerWAILA.class, hud, "facingBlockInfo", "Previous details");
        // Avoid needing a live plugin logger; recordFailure still records the error.
        setField(PlayerWAILA.class, hud, "lastFailureLogEpochMillis", System.currentTimeMillis());

        // The native HUD checks the plugin config even when a failed lookup
        // returns an empty snapshot. All tests need a valid plugin/config.
        SlimeHUD plugin = mock(SlimeHUD.class);
        when(plugin.getConfig()).thenReturn(config);
        pluginAccess = mockStatic(SlimeHUD.class);
        pluginAccess.when(SlimeHUD::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void restoreServer() throws Exception {
        if (pluginAccess != null) {
            pluginAccess.close();
        }
        setField(Bukkit.class, null, "server", previousServer);
    }

    @Test
    void failedBlockInspectionClearsPreviousTargetAndDisplay() throws Exception {
        inspectFailingBlock(7);

        assertEmptyDisplay();
        assertTrue(hud.getLastFailure().contains("block inspection: IllegalStateException"));
    }

    @Test
    void failedDroppedItemInspectionClearsPreviousTargetAndDisplay() throws Exception {
        Item item = proxy(Item.class, (object, method, arguments) -> switch (method.getName()) {
            case "getScheduler" -> immediateEntityScheduler();
            case "getItemStack" -> throw new IllegalStateException("Item inspection failed");
            default -> throw new AssertionError("Unexpected item call: " + method);
        });
        Method inspect = PlayerWAILA.class.getDeclaredMethod("inspectItem", long.class, Item.class);
        inspect.setAccessible(true);
        inspect.invoke(hud, 7L, item);

        assertEmptyDisplay();
        assertTrue(hud.getLastFailure().contains("dropped-item inspection: IllegalStateException"));
    }

    @Test
    void anOlderFailedInspectionCannotClearTheCurrentTarget() throws Exception {
        inspectFailingBlock(6);

        assertEquals("Previous block", hud.getFacingBlock());
        assertEquals("Previous details", hud.getFacingBlockInfo());
        assertTrue(visible.get());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void pausingOnlyClearsTheNativeDisplayChannel(DisplayMode nativeMode) throws Exception {
        setField(PlayerWAILA.class, hud, "displayMode", nativeMode);
        hud.setPaused(true);

        assertEmptyDisplay();
        assertEquals(nativeMode == DisplayMode.ACTIONBAR ? 1 : 0, actionBarClears.get());
    }

    @Test
    void crouchOnlyHidesExistingDisplayOnStandingUpdate() {
        config.set("waila.crouch-only", true);
        hud.run();
        assertEmptyDisplay();
    }

    @Test
    void releasingShiftClearsDisplayImmediatelyWithoutWaitingForTick() {
        config.set("waila.crouch-only", true);
        hud.onSneakChanged(false);
        assertEmptyDisplay();
    }

    @Test
    void normalModeDoesNotHideHudOnShiftRelease() {
        config.set("waila.crouch-only", false);
        hud.onSneakChanged(false);
        assertEquals("Previous block", hud.getFacingBlock());
        assertTrue(visible.get());
    }

    @Test
    void delayedInspectionCannotRenderAfterShiftRelease() throws Exception {
        config.set("waila.crouch-only", true);
        Class<?> snapshotClass = Class.forName(PlayerWAILA.class.getName() + "$HudSnapshot");
        var constructor = snapshotClass.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance("New block", "Energy: 42");
        Method apply = PlayerWAILA.class.getDeclaredMethod("applySnapshot", long.class, snapshotClass);
        apply.setAccessible(true);
        apply.invoke(hud, 7L, snapshot);
        assertEmptyDisplay();
    }

    private void inspectFailingBlock(long sequence) throws Exception {
        World world = proxy(World.class, (object, method, arguments) -> {
            if (method.getName().equals("getBlockAt")) {
                throw new IllegalStateException("Block inspection failed");
            }
            throw new AssertionError("Unexpected world call: " + method);
        });
        Method inspect = PlayerWAILA.class.getDeclaredMethod(
                "inspectBlock", long.class, Location.class, ItemStack.class);
        inspect.setAccessible(true);
        inspect.invoke(hud, sequence, new Location(world, 0, 64, 0), null);
    }

    private void assertEmptyDisplay() {
        assertEquals("", hud.getFacing());
        assertEquals("", hud.getFacingBlock());
        assertEquals("", hud.getFacingBlockInfo());
        assertFalse(visible.get(), "A failed target must not retain the previous block's visible label");
    }

    @SuppressWarnings("unchecked")
    private static EntityScheduler immediateEntityScheduler() {
        return proxy(EntityScheduler.class, (object, method, arguments) -> {
            if (method.getName().equals("run")) {
                ((Consumer<ScheduledTask>) arguments[1]).accept(null);
                return null;
            }
            throw new AssertionError("Unexpected entity scheduler call: " + method);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
