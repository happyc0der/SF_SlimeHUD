package io.github.schntgaispock.slimehud.waila;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.integration.WITIntegration;
import io.github.schntgaispock.slimehud.util.Util;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class PlayerWAILA {

    private record HudSnapshot(String name, String info) {}

    private final Player player;
    private final BossBar bossBar;
    private final boolean useAutoBossBarColor;
    private final boolean keepTextColors;
    private final AtomicLong requestSequence = new AtomicLong();

    private ScheduledTask task;
    private DisplayMode displayMode;
    private boolean paused;
    private boolean displayVisible;
    private String facing = "";
    private String facingBlock = "";
    private String facingBlockInfo = "";
    private volatile Integer maxDistanceOverride;
    private volatile Boolean vanillaEnabledOverride;
    private volatile String lastFailure = "";
    private volatile long lastFailureEpochMillis;
    private volatile long lastFailureLogEpochMillis;

    public PlayerWAILA(Player player) {
        this.player = player;

        String configuredColor = SlimeHUD.getInstance().getConfig().getString("waila.bossbar-color", "inherit");
        this.useAutoBossBarColor = "inherit".equalsIgnoreCase(configuredColor);
        this.keepTextColors = SlimeHUD.getInstance().getConfig().getBoolean("waila.use-original-colors", true);
        this.bossBar = Bukkit.createBossBar("", Util.pickBarColorFromColor(configuredColor), BarStyle.SOLID);
        this.bossBar.addPlayer(player);
        this.bossBar.setVisible(false);

        String defaultDisplay = SlimeHUD.getInstance().getConfig().getString(
                "waila.default-display",
                SlimeHUD.getInstance().getConfig().getString("waila.location", "bossbar"));
        String storedDisplay = SlimeHUD.getInstance().getPlayerData().getString(
                player.getUniqueId() + ".display", defaultDisplay);
        this.displayMode = DisplayMode.from(storedDisplay, DisplayMode.BOSSBAR);
    }

    public void start() {
        if (task != null) {
            return;
        }

        long period = Math.max(1L, SlimeHUD.getInstance().getConfig().getLong("waila.tick-rate", 5L));
        task = player.getScheduler().runAtFixedRate(
                SlimeHUD.getInstance(),
                scheduledTask -> runUpdateSafely(),
                null,
                1L,
                period);
    }

    public void stop() {
        requestSequence.incrementAndGet();
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearDisplay();
        bossBar.removeAll();
    }

    public void cancel() {
        stop();
    }

    public void run() {
        runUpdateSafely();
    }

    private void runUpdateSafely() {
        try {
            update();
        } catch (RuntimeException | LinkageError error) {
            recordFailure("native HUD update", error);
            try {
                clearFacing();
                clearDisplay();
            } catch (RuntimeException | LinkageError ignored) {
                // Preserve the repeating scheduler even if display cleanup also fails.
            }
        }
    }

    private void recordFailure(String phase, Throwable error) {
        long now = System.currentTimeMillis();
        String detail = error.getMessage();
        lastFailure = phase + ": " + error.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : " - " + detail);
        lastFailureEpochMillis = now;

        if (now - lastFailureLogEpochMillis >= 15_000L) {
            lastFailureLogEpochMillis = now;
            SlimeHUD.getInstance().getLogger().log(
                    Level.WARNING,
                    "SlimeHUD " + phase + " failed for " + player.getName()
                            + ". The HUD updater will stay alive and retry automatically.",
                    error);
        }
    }

    private void update() {
        if (paused) {
            return;
        }
        if (!player.isOnline() || !player.isValid()) {
            clearDisplay();
            return;
        }

        if (WITIntegration.shouldDelegate(player)) {
            requestSequence.incrementAndGet();
            clearFacing();
            clearDisplay();
            return;
        }

        long sequence = requestSequence.incrementAndGet();
        int maxDistance = getEffectiveMaxDistance();
        boolean showItems = SlimeHUD.getInstance().getConfig().getBoolean("items.enabled", true);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        double ownedDistance = HudRayTraceBounds.maximumOwnedDistance(eye.getWorld(), eye, direction, maxDistance);
        if (ownedDistance <= 0) {
            applySnapshot(sequence, new HudSnapshot("", ""));
            return;
        }
        RayTraceResult trace = eye.getWorld().rayTrace(
                eye,
                direction,
                ownedDistance,
                FluidCollisionMode.NEVER,
                true,
                HudRayTraceBounds.ENTITY_RAY_SIZE,
                entity -> showItems && entity instanceof Item);

        if (trace == null) {
            applySnapshot(sequence, new HudSnapshot("", ""));
            return;
        }

        if (trace.getHitEntity() instanceof Item droppedItem) {
            inspectItem(sequence, droppedItem);
            return;
        }

        Block hitBlock = trace.getHitBlock();
        if (hitBlock == null) {
            applySnapshot(sequence, new HudSnapshot("", ""));
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand().clone();
        inspectBlock(sequence, hitBlock.getLocation(), heldItem);
    }

    private int getEffectiveMaxDistance() {
        Integer override = maxDistanceOverride;
        int configured = SlimeHUD.getInstance().getConfig().getInt("waila.max-distance", 8);
        return Math.max(1, override == null ? configured : override);
    }

    private boolean isVanillaEnabled() {
        Boolean override = vanillaEnabledOverride;
        return override == null
                ? SlimeHUD.getInstance().getConfig().getBoolean("vanilla.enabled", true)
                : override;
    }

    private void inspectItem(long sequence, Item droppedItem) {
        droppedItem.getScheduler().run(
                SlimeHUD.getInstance(),
                scheduledTask -> {
                    try {
                        ItemStack stack = droppedItem.getItemStack().clone();
                        ItemInfoProvider.ItemHud itemHud =
                                ItemInfoProvider.describe(stack, SlimeHUD.getInstance().getConfig());
                        publishSnapshot(sequence, new HudSnapshot(itemHud.name(), itemHud.info()));
                    } catch (RuntimeException | LinkageError error) {
                        recordFailure("dropped-item inspection", error);
                    }
                },
                null);
    }

    private void inspectBlock(long sequence, Location target, ItemStack heldItem) {
        Bukkit.getRegionScheduler().execute(SlimeHUD.getInstance(), target, () -> {
            try {
                Block targetBlock = target.getBlock();
                if (targetBlock.getType().isAir()) {
                    publishSnapshot(sequence, new HudSnapshot("", ""));
                    return;
                }

                SlimefunItem slimefunItem = BlockStorage.check(targetBlock);
                if (slimefunItem != null) {
                    HudRequest request = new HudRequest(slimefunItem, target, player);
                    String name = SlimeHUD.getTranslationManager().getItemName(slimefunItem);
                    String info = SlimeHUD.getHudController().processRequest(request);

                    if (VanillaInfoProvider.isContainmentSpawner(slimefunItem, targetBlock)) {
                        name += VanillaInfoProvider.getToolSymbolSuffix(
                                targetBlock, SlimeHUD.getInstance().getConfig());
                        String toolInfo = VanillaInfoProvider.getToolInfo(
                                targetBlock, heldItem, SlimeHUD.getInstance().getConfig());
                        if (!toolInfo.isEmpty()) {
                            info = info.isEmpty() ? toolInfo : info + " &8| &7" + toolInfo;
                        }
                    }

                    publishSnapshot(sequence, new HudSnapshot(name, info));
                    return;
                }

                if (isVanillaEnabled()) {
                    String name = "&f" + VanillaInfoProvider.getName(targetBlock, SlimeHUD.getInstance().getConfig());
                    String info = VanillaInfoProvider.getInfo(
                            targetBlock, heldItem, SlimeHUD.getInstance().getConfig());
                    publishSnapshot(sequence, new HudSnapshot(name, info));
                    return;
                }

                publishSnapshot(sequence, new HudSnapshot("", ""));
            } catch (RuntimeException | LinkageError error) {
                recordFailure("block inspection", error);
            }
        });
    }

    private void publishSnapshot(long sequence, HudSnapshot snapshot) {
        player.getScheduler().run(
                SlimeHUD.getInstance(),
                scheduledTask -> applySnapshot(sequence, snapshot),
                null);
    }

    private void applySnapshot(long sequence, HudSnapshot snapshot) {
        if (sequence != requestSequence.get() || paused || !player.isOnline()) {
            return;
        }

        facingBlock = snapshot.name();
        facingBlockInfo = snapshot.info();
        if (facingBlock.isEmpty()) {
            facing = "";
        } else {
            buildFacingText();
        }
        renderCurrent();
    }

    private void buildFacingText() {
        facing = ChatColor.translateAlternateColorCodes(
                '&', facingBlock + (facingBlockInfo.isEmpty() ? "" : " &7| " + facingBlockInfo));
    }

    private void renderCurrent() {
        if (displayMode == DisplayMode.BOSSBAR) {
            showBossBar();
        } else {
            showActionBar();
        }
    }

    private void showBossBar() {
        if (facing.isEmpty()) {
            clearDisplay();
            return;
        }

        bossBar.setVisible(true);
        bossBar.setTitle(keepTextColors ? facing : ChatColor.stripColor(facing));
        if (useAutoBossBarColor) {
            bossBar.setColor(Util.pickBarColorFromName(facing));
        }
        displayVisible = true;
    }

    private void showActionBar() {
        bossBar.setVisible(false);
        if (facing.isEmpty()) {
            clearDisplay();
            return;
        }

        player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent.fromLegacyText(keepTextColors ? facing : ChatColor.stripColor(facing)));
        displayVisible = true;
    }

    private void clearDisplay() {
        bossBar.setVisible(false);
        if (!displayVisible) {
            return;
        }
        if (player.isOnline()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(""));
        }
        displayVisible = false;
    }

    private void clearFacing() {
        facingBlock = "";
        facingBlockInfo = "";
        facing = "";
    }

    public Player getPlayer() {
        return player;
    }

    public BossBar getWAILABar() {
        return bossBar;
    }

    public String getFacing() {
        return facing;
    }

    public String getFacingBlock() {
        return facingBlock;
    }

    public String getFacingBlockInfo() {
        return facingBlockInfo;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        requestSequence.incrementAndGet();
        if (paused) {
            clearFacing();
            clearDisplay();
        }
    }

    public PlayerWAILA setVisible(boolean visible) {
        if (!visible) {
            clearDisplay();
        } else if (!paused && !facing.isEmpty()) {
            renderCurrent();
        }
        return this;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(DisplayMode displayMode) {
        if (this.displayMode == displayMode) {
            return;
        }
        clearDisplay();
        this.displayMode = displayMode;
    }

    /**
     * Applies an optional per-player range override supplied by another addon,
     * such as JustEnoughGuide. Pass null to return to config.yml.
     */
    public void setMaxDistanceOverride(Integer maxDistance) {
        this.maxDistanceOverride = maxDistance == null ? null : Math.max(1, maxDistance);
        requestSequence.incrementAndGet();
    }

    public Integer getMaxDistanceOverride() {
        return maxDistanceOverride;
    }

    /**
     * Applies an optional per-player vanilla-block override supplied by another
     * addon. Pass null to return to config.yml.
     */
    public void setVanillaEnabledOverride(Boolean enabled) {
        this.vanillaEnabledOverride = enabled;
        requestSequence.incrementAndGet();
    }

    public Boolean getVanillaEnabledOverride() {
        return vanillaEnabledOverride;
    }

    public boolean isTaskRunning() {
        return task != null;
    }

    public String getLastFailure() {
        return lastFailure;
    }

    public long getLastFailureEpochMillis() {
        return lastFailureEpochMillis;
    }

    public void clearExternalOverrides() {
        maxDistanceOverride = null;
        vanillaEnabledOverride = null;
        requestSequence.incrementAndGet();
    }
}
