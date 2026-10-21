package io.github.schntgaispock.slimehud.integration;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.waila.HudRequest;
import io.github.schntgaispock.slimehud.waila.PlayerWAILA;
import io.github.schntgaispock.slimehud.waila.VanillaInfoProvider;
import io.github.schntgaispock.slimehud.waila.WAILAManager;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.logging.Level;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Optional bridge for the What Is That? (WIT) plugin.
 *
 * <p>The bridge deliberately uses reflection so WIT never becomes a hard
 * dependency. When WIT is available, its own display loop remains responsible
 * for the visible BossBar/ActionBar and SF_SlimeHUD contributes Slimefun
 * machine information through WIT's public block-handler API.</p>
 */
public final class WITIntegration implements Listener {

    private static final String WIT_PLUGIN_NAME = "WIT";
    private static WITIntegration instance;

    private final SlimeHUD plugin;
    private final Set<UUID> bridgeFailures = ConcurrentHashMap.newKeySet();
    private final Set<UUID> warnedBridgeFailures = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> delegatedDisplays = new ConcurrentHashMap<>();
    private final Map<UUID, PendingDisplayCleanup> pendingDisplayCleanups = new ConcurrentHashMap<>();
    private final AtomicLong cleanupSequence = new AtomicLong();

    private record PendingDisplayCleanup(long sequence, String type, Method removeBar, Method setLookingAt) {}

    private Plugin witPlugin;
    private BiFunction<Block, Player, Boolean> blockHandler;
    private Object witAddonProxy;

    private Constructor<?> infoConstructor;
    private Method infoSetName;
    private Method infoAddSuffix;
    private Method apiAddBlockHandler;
    private Method apiRemoveBlockHandler;
    private Method apiUpdateBar;
    private Method apiRegisterAddon;
    private Method apiUnregisterAddon;
    private Method witGetSettings;
    private Method witIsDisabledWorld;
    private Method witIsHidden;
    private Method witGetLookingAt;
    private Method witSetLookingAt;
    private Method witGetDisplays;
    private Method witRemoveBar;
    private Field settingsDisabled;
    private Field settingsType;
    private Field witSneakingMode;
    private Field witBlocksEnabled;
    private Method witIsAllowedBlock;

    private boolean hooked;

    private WITIntegration(SlimeHUD plugin) {
        this.plugin = plugin;
    }

    public static void setup() {
        if (instance != null) {
            return;
        }

        instance = new WITIntegration(SlimeHUD.getInstance());
        Bukkit.getPluginManager().registerEvents(instance, SlimeHUD.getInstance());
        instance.tryHook();
    }

    public static void shutdown() {
        if (instance == null) {
            return;
        }

        instance.unhook();
        HandlerList.unregisterAll(instance);
        instance = null;
    }

    public static void refresh() {
        if (instance == null) {
            return;
        }

        instance.unhook();
        instance.tryHook();
    }

    public static boolean isHooked() {
        return instance != null && instance.hooked;
    }

    public static boolean hasBridgeFailure(Player player) {
        return instance != null && instance.bridgeFailures.contains(player.getUniqueId());
    }

    /**
     * Returns true only while WIT is actively responsible for this player's
     * display. If WIT is hidden, disabled for the player/world, or unavailable,
     * SF_SlimeHUD keeps rendering its own HUD.
     */
    public static boolean shouldDelegate(Player player) {
        return instance != null && instance.shouldDelegateInternal(player);
    }

    /**
     * Updates display ownership on the player's scheduler. Unlike the status
     * query, this also removes a previous WIT display when the native HUD takes
     * over. WIT's sneaking mode stops updating without removing its last bar.
     */
    public static boolean updateDisplayOwnership(Player player) {
        return instance != null && instance.updateDisplayOwnershipInternal(player);
    }

    /** Returns the active WIT display channel after ownership has been updated. */
    public static boolean isUsingActionBar(Player player) {
        return instance != null && "actionbar".equals(instance.delegatedDisplays.get(player.getUniqueId()));
    }

    private boolean integrationEnabled() {
        return plugin.getConfig().getBoolean("integrations.what-is-that.enabled", true);
    }

    private void tryHook() {
        if (hooked || !integrationEnabled()) {
            return;
        }

        Plugin candidate = Bukkit.getPluginManager().getPlugin(WIT_PLUGIN_NAME);
        if (candidate == null || !candidate.isEnabled()) {
            return;
        }

        try {
            ClassLoader loader = candidate.getClass().getClassLoader();
            Class<?> apiClass = loader.loadClass("com.github.darksoulq.wit.api.API");
            Class<?> infoClass = loader.loadClass("com.github.darksoulq.wit.api.Info");
            Class<?> witPluginClass = loader.loadClass("com.github.darksoulq.wit.api.WITPlugin");
            Class<?> witListenerClass = loader.loadClass("com.github.darksoulq.wit.WITListener");
            Class<?> settingsClass = loader.loadClass("com.github.darksoulq.wit.WITListener$PlayerSettings");
            Class<?> itemGroupsClass = loader.loadClass("com.github.darksoulq.wit.misc.ItemGroups");
            Class<?> displayManagerClass = loader.loadClass("com.github.darksoulq.wit.display.DisplayManager");

            infoConstructor = infoClass.getConstructor();
            infoSetName = infoClass.getMethod("setName", Component.class);
            infoAddSuffix = infoClass.getMethod("addSuffix", Component.class);

            apiAddBlockHandler = apiClass.getMethod("addBlockHandler", BiFunction.class);
            apiRemoveBlockHandler = apiClass.getMethod("removeBlockHandler", BiFunction.class);
            apiUpdateBar = apiClass.getMethod("updateBar", infoClass, Player.class);
            apiRegisterAddon = apiClass.getMethod("registerAddon", witPluginClass);
            apiUnregisterAddon = apiClass.getMethod("unregisterAddon", witPluginClass);

            witGetSettings = witListenerClass.getMethod("getSettings", Player.class);
            witIsDisabledWorld = witListenerClass.getMethod("isDisabledWorld", World.class);
            witIsHidden = witListenerClass.getMethod("isHidden");
            witGetLookingAt = witListenerClass.getMethod("getLookingAt", Player.class);
            witSetLookingAt = witListenerClass.getMethod("setLookingAt", Player.class, infoClass);
            witGetDisplays = displayManagerClass.getMethod("getDisplays");
            witRemoveBar = displayManagerClass.getMethod("removeBar", Player.class, String.class);
            settingsDisabled = settingsClass.getField("disabled");
            settingsType = settingsClass.getField("type");
            witSneakingMode = witListenerClass.getField("SNEAKING_MODE");
            witBlocksEnabled = witListenerClass.getField("BLOCKS_ENABLED");
            witIsAllowedBlock = itemGroupsClass.getMethod("isAllowedBlock", Material.class);

            witPlugin = candidate;
            blockHandler = this::handleBlock;
            apiAddBlockHandler.invoke(null, blockHandler);

            witAddonProxy = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {witPluginClass},
                    (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "onWITReload" -> {
                                reRegisterHandler();
                                yield null;
                            }
                            case "toString" -> "SF_SlimeHUD WIT bridge";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                            default -> null;
                        };
                    });
            apiRegisterAddon.invoke(null, witAddonProxy);

            hooked = true;
            plugin.getLogger().info(
                    "What Is That? bridge enabled. WIT will display Slimefun machine data supplied by SF_SlimeHUD.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // Registration may already have added the handler before a later
            // API call failed. Keep the references until it has been removed.
            unhook();
            plugin.getLogger().log(
                    Level.WARNING,
                    "What Is That? was found, but its API could not be linked. SF_SlimeHUD will keep using its native HUD.",
                    error);
        }
    }

    private void reRegisterHandler() {
        if (!hooked || blockHandler == null || apiAddBlockHandler == null) {
            return;
        }

        try {
            apiAddBlockHandler.invoke(null, blockHandler);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unhook();
            plugin.getLogger().log(Level.WARNING,
                    "Could not re-register the SF_SlimeHUD handler after a WIT reload. The native HUD remains available.",
                    error);
        }
    }

    private boolean handleBlock(Block block, Player player) {
        if (!hooked || !integrationEnabled() || !isSlimeHudEnabledFor(player)) {
            return false;
        }

        boolean ownedBlock = false;
        try {
            if (getWitDisplayType(player) == null) {
                bridgeFailures.remove(player.getUniqueId());
                return false;
            }
            SlimefunItem item = BlockStorage.check(block);
            if (item != null) {
                ownedBlock = true;
                HudRequest request = new HudRequest(item, block.getLocation(), player);
                String name = SlimeHUD.getTranslationManager().getItemName(item);
                String info = SlimeHUD.getHudController().processRequest(request);

                if (VanillaInfoProvider.isContainmentSpawner(item, block)) {
                    name += VanillaInfoProvider.getToolSymbolSuffix(block, plugin.getConfig());
                    String toolInfo = VanillaInfoProvider.getToolInfo(
                            block, player.getInventory().getItemInMainHand().clone(), plugin.getConfig());
                    if (!toolInfo.isEmpty()) {
                        info = info.isEmpty() ? toolInfo : info + " &8| &7" + toolInfo;
                    }
                }

                updateWitBar(name, info, player);
                bridgeFailures.remove(player.getUniqueId());
                return true;
            }

            if (!plugin.getConfig().getBoolean("integrations.what-is-that.use-slimehud-vanilla", true)
                    || !isVanillaEnabledFor(player)
                    || !witAllowsVanillaBlock(block)) {
                bridgeFailures.remove(player.getUniqueId());
                return false;
            }

            ownedBlock = true;
            ItemStack heldItem = player.getInventory().getItemInMainHand().clone();
            String name = "&f" + VanillaInfoProvider.getName(block, plugin.getConfig());
            String info = VanillaInfoProvider.getInfo(block, heldItem, plugin.getConfig());
            updateWitBar(name, info, player);
            bridgeFailures.remove(player.getUniqueId());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            UUID playerId = player.getUniqueId();
            if (ownedBlock) {
                if (bridgeFailures.add(playerId)) {
                    String previousDisplay = delegatedDisplays.remove(playerId);
                    if (previousDisplay == null) {
                        previousDisplay = getWitDisplayTypeSafely(player);
                    }
                    clearWitDisplay(player, previousDisplay, witRemoveBar, witSetLookingAt);
                } else {
                    // A retry claims its renderer before calling WIT. It did
                    // not acquire a working display, so do not make the next
                    // native update remove/blank that channel again.
                    delegatedDisplays.remove(playerId);
                    // API.updateBar records LOOKING_AT before rendering, so a
                    // repeated renderer failure must not look like recovery.
                    clearWitTarget(player, witSetLookingAt);
                }
            } else {
                bridgeFailures.remove(playerId);
            }
            if (warnedBridgeFailures.add(playerId)) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "The WIT bridge failed for " + player.getName()
                                + (ownedBlock
                                        ? ". SF_SlimeHUD will fall back to its native HUD and keep retrying the bridge."
                                        : ". WIT will continue with its other block handlers."),
                        error);
            }
            // A failed bridge-owned block is rendered by the native fallback.
            // Returning false would also make WIT render its vanilla fallback.
            return ownedBlock;
        }
    }

    private void updateWitBar(String name, String info, Player player) throws ReflectiveOperationException {
        Object witInfo = infoConstructor.newInstance();
        infoSetName.invoke(witInfo, legacyComponent(name));
        if (!info.isBlank()) {
            infoAddSuffix.invoke(witInfo, legacyComponent(info));
        }
        String display = getWitDisplayType(player);
        if (display != null) {
            claimDisplay(player, display);
        }
        apiUpdateBar.invoke(null, witInfo, player);
    }

    private boolean isVanillaEnabledFor(Player player) {
        PlayerWAILA waila = WAILAManager.getInstance().getWaila(player);
        if (waila != null && waila.getVanillaEnabledOverride() != null) {
            return waila.getVanillaEnabledOverride();
        }
        return plugin.getConfig().getBoolean("vanilla.enabled", true);
    }

    private boolean witAllowsVanillaBlock(Block block) {
        try {
            if (witBlocksEnabled != null && !witBlocksEnabled.getBoolean(null)) {
                return false;
            }
            return witIsAllowedBlock == null || (Boolean) witIsAllowedBlock.invoke(null, block.getType());
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('§', '&'));
    }

    private boolean shouldDelegateInternal(Player player) {
        return getDelegatedDisplay(player) != null;
    }

    private String getDelegatedDisplay(Player player) {
        if (!hooked || !integrationEnabled() || !isSlimeHudEnabledFor(player)
                || witPlugin == null || !witPlugin.isEnabled()) {
            return null;
        }

        try {
            Object settings = witGetSettings.invoke(null, player);
            if (settings != null && settingsDisabled.getBoolean(settings)) {
                return null;
            }

            if ((Boolean) witIsDisabledWorld.invoke(null, player.getWorld())) {
                return null;
            }

            if ((Boolean) witIsHidden.invoke(null)) {
                return null;
            }

            if (witSneakingMode.getBoolean(null) && !player.isSneaking()) {
                return null;
            }

            String display = getWitDisplayType(settings);
            if (display == null) {
                return null;
            }

            // A different WIT provider (for example an entity handler) may
            // recover without invoking our block handler again. A failed
            // bridge render explicitly clears this target first.
            if (bridgeFailures.contains(player.getUniqueId())
                    && witGetLookingAt.invoke(null, player) == null) {
                return null;
            }
            return display;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // Fail open to SF_SlimeHUD's native display so an integration issue
            // can never leave the player without a HUD.
            return null;
        }
    }

    private String getWitDisplayType(Player player) throws ReflectiveOperationException {
        return getWitDisplayType(witGetSettings.invoke(null, player));
    }

    private String getWitDisplayType(Object settings) throws ReflectiveOperationException {
        if (settings == null || !(settingsType.get(settings) instanceof String type)) {
            return null;
        }
        Object displays = witGetDisplays.invoke(null);
        return displays instanceof Map<?, ?> registry && registry.get(type) != null ? type : null;
    }

    private String getWitDisplayTypeSafely(Player player) {
        try {
            return getWitDisplayType(player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private boolean updateDisplayOwnershipInternal(Player player) {
        UUID playerId = player.getUniqueId();
        String display = getDelegatedDisplay(player);
        if (display != null) {
            bridgeFailures.remove(playerId);
            claimDisplay(player, display);
            return true;
        }

        String previousDisplay = delegatedDisplays.remove(playerId);
        if (previousDisplay != null) {
            clearWitDisplay(player, previousDisplay, witRemoveBar, witSetLookingAt);
        }
        PendingDisplayCleanup pending = pendingDisplayCleanups.remove(playerId);
        if (pending != null) {
            clearWitDisplay(player, pending.type(), pending.removeBar(), pending.setLookingAt());
        }
        return false;
    }

    private void claimDisplay(Player player, String display) {
        UUID playerId = player.getUniqueId();
        PendingDisplayCleanup pending = pendingDisplayCleanups.remove(playerId);
        if (pending != null && !pending.type().equals(display)) {
            clearWitDisplay(player, pending.type(), pending.removeBar(), null);
        }
        String previousDisplay = delegatedDisplays.put(playerId, display);
        if (previousDisplay != null && !previousDisplay.equals(display)) {
            clearWitDisplay(player, previousDisplay, witRemoveBar, null);
        }
    }

    private static void clearWitTarget(Player player, Method setLookingAt) {
        if (setLookingAt != null) {
            try {
                setLookingAt.invoke(null, player, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // WIT may already be disabling or have an incompatible API.
            }
        }
    }

    private static void clearWitDisplay(Player player, String type, Method removeBar, Method setLookingAt) {
        if (type != null && removeBar != null) {
            try {
                removeBar.invoke(null, player, type);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Preserve native fallback if the optional renderer fails.
            }
        }
        clearWitTarget(player, setLookingAt);
    }

    private void releaseTrackedDisplays() {
        Map<UUID, String> previousDisplays = Map.copyOf(delegatedDisplays);
        delegatedDisplays.clear();
        if (!plugin.isEnabled()) {
            pendingDisplayCleanups.clear();
            return;
        }
        if (witPlugin == null || !witPlugin.isEnabled()) {
            return;
        }

        Method removeBar = witRemoveBar;
        Method setLookingAt = witSetLookingAt;
        previousDisplays.forEach((playerId, type) -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                PendingDisplayCleanup cleanup = new PendingDisplayCleanup(
                        cleanupSequence.incrementAndGet(), type, removeBar, setLookingAt);
                pendingDisplayCleanups.put(playerId, cleanup);
                // Reload can run from the console/global region. The display
                // belongs to the player's entity scheduler on Folia.
                player.getScheduler().run(plugin, task -> {
                    if (pendingDisplayCleanups.remove(playerId, cleanup)) {
                        clearWitDisplay(player, type, removeBar, setLookingAt);
                    }
                }, null);
            }
        });
    }

    private boolean isSlimeHudEnabledFor(Player player) {
        if (plugin.getConfig().getBoolean("waila.disabled", false)) {
            return false;
        }

        boolean disabledWorld = plugin.getConfig()
                .getStringList("waila.disabled-in")
                .stream()
                .anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName())
                        || world.equalsIgnoreCase(player.getWorld().getKey().toString()));
        if (disabledWorld) {
            return false;
        }

        return plugin.getPlayerData().getBoolean(player.getUniqueId() + ".waila", true);
    }

    private void unhook() {
        releaseTrackedDisplays();
        if (blockHandler != null && apiRemoveBlockHandler != null) {
            try {
                apiRemoveBlockHandler.invoke(null, blockHandler);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // WIT may already be part-way through disabling.
            }
        }

        if (witAddonProxy != null && apiUnregisterAddon != null) {
            try {
                apiUnregisterAddon.invoke(null, witAddonProxy);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // WIT may already be part-way through disabling.
            }
        }

        if (hooked) {
            plugin.getLogger().info("What Is That? bridge disabled; SF_SlimeHUD native display is active again.");
        }
        clearBridgeReferences();
    }

    private void clearBridgeReferences() {
        hooked = false;
        witPlugin = null;
        blockHandler = null;
        witAddonProxy = null;
        infoConstructor = null;
        infoSetName = null;
        infoAddSuffix = null;
        apiAddBlockHandler = null;
        apiRemoveBlockHandler = null;
        apiUpdateBar = null;
        apiRegisterAddon = null;
        apiUnregisterAddon = null;
        witGetSettings = null;
        witIsDisabledWorld = null;
        witIsHidden = null;
        witGetLookingAt = null;
        witSetLookingAt = null;
        witGetDisplays = null;
        witRemoveBar = null;
        settingsDisabled = null;
        settingsType = null;
        witSneakingMode = null;
        witBlocksEnabled = null;
        witIsAllowedBlock = null;
        bridgeFailures.clear();
        warnedBridgeFailures.clear();
        delegatedDisplays.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        bridgeFailures.remove(playerId);
        warnedBridgeFailures.remove(playerId);
        delegatedDisplays.remove(playerId);
        pendingDisplayCleanups.remove(playerId);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (WIT_PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) {
            tryHook();
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (WIT_PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) {
            unhook();
        }
    }
}
