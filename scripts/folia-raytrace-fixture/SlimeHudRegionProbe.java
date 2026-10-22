package io.github.schntgaispock.slimehud.probe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schntgaispock.slimehud.waila.DisplayMode;
import io.github.schntgaispock.slimehud.waila.PlayerWAILA;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import sun.misc.Unsafe;

/**
 * Test-only production-updater probe. The accessor fixture is not a connected player:
 * its test-only CraftPlayer wrapper delegates accessors, the raw native handle,
 * and scheduling to an actual Folia-owned pig, while native
 * World/Block/Entity APIs and the installed SlimeHUD updater remain unmodified.
 */
public final class SlimeHudRegionProbe extends JavaPlugin {
    private final Map<String, Object> proof = new LinkedHashMap<>();
    private boolean started;
    private Pig owner;
    private PlayerWAILA hud;
    private Location nearbyBlock;
    private Location farBlock;
    private Item nearbyItem;
    private boolean expectFailure;
    private boolean singleRegion;
    private int crossingDistance;
    private int productionUpdates;
    private int ownedUpdateChecks;
    private String expectedMode;
    private String bootToken;
    private String sourceCommit;
    private String expectedAddonHash;
    private String expectedCoreHash;

    @Override
    public void onEnable() {
        try {
            JsonObject request = JsonParser.parseString(Files.readString(getDataFolder().toPath().resolve("request.json"))).getAsJsonObject();
            expectedMode = request.get("mode").getAsString();
            bootToken = request.get("boot_token").getAsString();
            sourceCommit = request.get("source_commit").getAsString();
            expectedAddonHash = request.get("addon_sha256").getAsString();
            expectedCoreHash = request.get("core_sha256").getAsString();
            require(bootToken.matches("[0-9a-f]{32}") && sourceCommit.matches("[0-9a-f]{40}"), "Missing pre-bound boot/source identity");
            receiptIdentity();
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid native probe request", failure);
        }
    }

    @Override
    public void onDisable() {
        try {
            Map<String, Object> shutdown = receiptIdentity();
            boolean finished = "PASS".equals(proof.get("status"));
            shutdown.put("probe_finished", finished);
            shutdown.put("server_stopping", Bukkit.isStopping());
            shutdown.put("status", finished && Bukkit.isStopping() ? "PASS" : "FAIL");
            writeReceipt("shutdown.json", shutdown);
            getLogger().info("SLIMEHUD_REGION_PROBE_SHUTDOWN " + bootToken + " " + Bukkit.isStopping());
        } catch (Exception failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "Native probe shutdown receipt failed", failure);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (started || args.length != 1 || !(args[0].equals("control") || args[0].equals("candidate") || args[0].equals("single-region"))) {
            return false;
        }
        require(args[0].equals(expectedMode), "Command mode differs from the pre-bound request");
        started = true;
        expectFailure = args[0].equals("control");
        singleRegion = args[0].equals("single-region");
        try {
            proof.putAll(receiptIdentity());
        } catch (Exception failure) {
            fail(failure);
            return true;
        }
        proof.put("connected_player", false);
        proof.put("fixture", "Synthetic player accessors in a CraftPlayer wrapper backed by a native pig handle and scheduler; real production HUD updater");
        proof.put("server", Bukkit.getVersion());
        proof.put("server_name", Bukkit.getName());
        proof.put("minecraft_version", Bukkit.getMinecraftVersion());
        proof.put("java", System.getProperty("java.version"));
        proof.put("slimehud_version", Bukkit.getPluginManager().getPlugin("SlimeHUD").getPluginMeta().getVersion());
        proof.put("slimefun_version", Bukkit.getPluginManager().getPlugin("Slimefun").getPluginMeta().getVersion());
        // The native region/target fixture uses a synthetic CraftPlayer backed by
        // a real pig. It cannot answer a real player's isSneaking() accessor.
        // Record actual packaged defaults before bypassing crouch gating ONLY
        // for the synthetic ray/region-ownership test. Never save this override.
        org.bukkit.plugin.Plugin installedHud = Bukkit.getPluginManager().getPlugin("SlimeHUD");
        require(installedHud instanceof JavaPlugin, "SlimeHUD plugin is not available");
        JavaPlugin hudPlugin = (JavaPlugin) installedHud;
        proof.put("packaged_global_disabled", hudPlugin.getConfig().getBoolean("waila.disabled", true));
        proof.put("packaged_default_enabled", hudPlugin.getConfig().getBoolean("waila.default-enabled", true));
        proof.put("packaged_crouch_only", hudPlugin.getConfig().getBoolean("waila.crouch-only", false));
        hudPlugin.getConfig().set("waila.crouch-only", false);
        proof.put("fixture_crouch_only_override", false);
        World world = Bukkit.getWorlds().getFirst();
        world.getChunkAtAsync(0, 0).whenComplete((chunk, error) -> {
            if (error != null) {
                fail(error);
                return;
            }
            Bukkit.getRegionScheduler().execute(this, world, 0, 0, () -> {
                try {
                    Location anchor = new Location(world, 8.5, 128.5, 8.5);
                    owner = world.spawn(anchor, Pig.class, pig -> {
                        pig.setAI(false);
                        pig.setGravity(false);
                        pig.setInvulnerable(true);
                    });
                    world.addPluginChunkTicket(0, 0, this);
                    owner.getScheduler().run(this, task -> startLocal(), null);
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        });
        return true;
    }

    private void startLocal() {
        try {
            require(Bukkit.isOwnedByCurrentRegion(owner), "Native entity must be owned by this thread");
            proof.put("owner_thread", Thread.currentThread().getName());
            proof.put("native_entity_owned", true);
            hud = createProductionUpdater();
            Location eye = eye();
            nearbyBlock = eye.clone().add(4, 0, 0);
            require(Bukkit.isOwnedByCurrentRegion(nearbyBlock), "Nearby control block must be owned");
            nearbyBlock.getBlock().setType(Material.STONE);
            hud.setMaxDistanceOverride(8);
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkLocal(), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkLocal() {
        try {
            require(hud.getLastFailure().isEmpty(), "Local production HUD failed: " + hud.getLastFailure());
            require(hud.getFacingBlock().toLowerCase().contains("stone"), "Local native HUD did not render the stone block: " + hud.getFacingBlock());
            proof.put("local_block_rendered", hud.getFacingBlock());
            nearbyBlock.getBlock().setType(Material.AIR);
            Location eye = eye();
            int chunkX = eye.getBlockX() >> 4;
            int chunkZ = eye.getBlockZ() >> 4;
            int distance;
            if (singleRegion) {
                distance = 508;
                require(Bukkit.isOwnedByCurrentRegion(eye.getWorld(), chunkX, chunkZ, chunkX + 33, chunkZ),
                        "Single-region control must own the complete long ray");
                proof.put("single_region_long_ray_owned", true);
            } else {
                int firstUnowned = chunkX + 1;
                while (firstUnowned <= chunkX + 2048 && Bukkit.isOwnedByCurrentRegion(eye.getWorld(), firstUnowned, chunkZ)) {
                    firstUnowned++;
                }
                require(firstUnowned <= chunkX + 2048, "Fixture could not find an unowned chunk");
                distance = (int) Math.ceil(firstUnowned * 16.0 - eye.getX() + 4.0);
                proof.put("first_unowned_chunk", Map.of("x", firstUnowned, "z", chunkZ));
                proof.put("unowned_target_confirmed_before_query", !Bukkit.isOwnedByCurrentRegion(eye.getWorld(), firstUnowned, chunkZ));
            }
            crossingDistance = distance;
            proof.put("origin", Map.of("x", eye.getX(), "y", eye.getY(), "z", eye.getZ()));
            proof.put("configured_range", distance);
            hud.setMaxDistanceOverride(distance);
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkCrossing(), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkCrossing() {
        try {
            String failure = hud.getLastFailure();
            proof.put("production_updater_failure", failure);
            if (expectFailure) {
                require(!failure.isBlank(), "Unmodified production updater did not expose the ownership failure");
                require(failure.startsWith("native HUD update: IllegalStateException - Thread failed main thread check: Cannot getEntities asynchronously"),
                        "Failure was not the reproduced native entity-query ownership diagnostic: " + failure);
            } else {
                require(failure.isEmpty(), "Candidate queried an unsafe native ray: " + failure);
                require(hud.getFacingBlock().isEmpty(), "Empty ray must not retain a stale block label");
                proof.put("empty_long_ray_cleared", true);
            }
            proof.put("crossing_outcome_matches_expectation", true);
            long previousFailure = hud.getLastFailureEpochMillis();
            proof.put("last_failure_before_recovery", previousFailure);
            nearbyBlock.getBlock().setType(Material.STONE);
            hud.setMaxDistanceOverride(8);
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkRecovery(previousFailure), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkRecovery(long previousFailure) {
        try {
            require(hud.getFacingBlock().toLowerCase().contains("stone"), "Local HUD did not recover");
            require(hud.getLastFailureEpochMillis() == previousFailure, "Recovery generated an additional error");
            proof.put("local_display_recovers", true);
            if (expectFailure) {
                finish();
                return;
            }
            hud.setMaxDistanceOverride(crossingDistance);
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkLongRangeBlock(), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkLongRangeBlock() {
        try {
            require(hud.getLastFailure().isEmpty(), "Long-range local block inspection failed");
            require(hud.getFacingBlock().toLowerCase().contains("stone"), "Clipping removed a nearby block target");
            proof.put("long_range_nearby_block_rendered", hud.getFacingBlock());
            nearbyBlock.getBlock().setType(Material.AIR);
            nearbyItem = owner.getWorld().dropItem(eye().add(4, 0, 0), new ItemStack(Material.DIAMOND), item -> {
                item.setGravity(false);
                item.setVelocity(new Vector());
                item.setPickupDelay(Integer.MAX_VALUE);
            });
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkLongRangeItem(), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkLongRangeItem() {
        try {
            require(hud.getLastFailure().isEmpty(), "Long-range dropped-item inspection failed: " + hud.getLastFailure());
            require(hud.getFacingBlock().toLowerCase().contains("diamond"), "Clipping removed a nearby dropped-item target: " + hud.getFacingBlock());
            proof.put("long_range_nearby_item_rendered", hud.getFacingBlock());
            if (singleRegion) {
                nearbyItem.remove();
                nearbyItem = null;
                farBlock = eye().add(24, 0, 0);
                require(Bukkit.isOwnedByCurrentRegion(farBlock), "The real far target must be owned");
                proof.put("far_target_owned", true);
                proof.put("far_target_distance", eye().distance(farBlock));
                farBlock.getBlock().setType(Material.STONE);
                hud.setMaxDistanceOverride(8);
                runHud();
                owner.getScheduler().runDelayed(this, task -> checkFarOutsideDefaultRange(), null, 6L);
                return;
            }
            finish();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkFarOutsideDefaultRange() {
        try {
            require(hud.getLastFailure().isEmpty(), "Default-range far-target control failed");
            require(hud.getFacingBlock().isEmpty(), "The default eight-block ray reached the 24-block target");
            proof.put("default_range_far_target_absent", true);
            hud.setMaxDistanceOverride(crossingDistance);
            runHud();
            owner.getScheduler().runDelayed(this, task -> checkFarTarget(), null, 6L);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void checkFarTarget() {
        try {
            require(hud.getLastFailure().isEmpty(), "Native far-target inspection failed");
            require(hud.getFacingBlock().toLowerCase().contains("stone"), "Extended range lost the real 24-block target");
            proof.put("extended_range_far_target_rendered", hud.getFacingBlock());
            finish();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void runHud() {
        require(Bukkit.isOwnedByCurrentRegion(owner), "Every production update must run on the native entity owner");
        ownedUpdateChecks++;
        productionUpdates++;
        hud.run();
    }

    private void finish() throws Exception {
        proof.put("status", "PASS");
        proof.put("production_updates", productionUpdates);
        proof.put("owned_update_checks", ownedUpdateChecks);
        proof.put("last_failure_after_recovery", hud.getLastFailureEpochMillis());
        hud.stop();
        nearbyBlock.getBlock().setType(Material.AIR);
        if (farBlock != null) farBlock.getBlock().setType(Material.AIR);
        if (nearbyItem != null) nearbyItem.remove();
        owner.remove();
        save();
        getLogger().info("SLIMEHUD_REGION_PROBE_PASS " + expectedMode + " " + bootToken);
    }

    private Location eye() {
        Location eye = owner.getEyeLocation();
        eye.setDirection(new Vector(1, 0, 0));
        return eye;
    }

    private PlayerWAILA createProductionUpdater() throws Exception {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemInMainHand" -> new ItemStack(Material.DIAMOND_PICKAXE);
                    case "toString" -> "HUD fixture inventory";
                    default -> throw new AssertionError("Unexpected player inventory access: " + method.getName());
                });
        Player.Spigot spigot = new Player.Spigot() {
            @Override public void sendMessage(ChatMessageType position, BaseComponent... components) { }
        };
        UUID playerId = UUID.nameUUIDFromBytes("SlimeHUDRegionFixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        OwnerBackedPlayer player = (OwnerBackedPlayer) unsafe.allocateInstance(OwnerBackedPlayer.class);
        player.owner = owner;
        player.probe = this;
        player.playerId = playerId;
        player.inventory = inventory;
        player.messages = spigot;
        // CraftEntity#getScheduler is final. Reuse the owner's real scheduler
        // rather than creating an unregistered fake player entity/scheduler.
        Field scheduler = CraftEntity.class.getDeclaredField("apiScheduler");
        scheduler.setAccessible(true);
        scheduler.set(player, scheduler.get(owner));
        require(player.getHandleRaw() == ((CraftEntity) owner).getHandleRaw(),
                "Synthetic player must retain the actual native owner handle");
        require(player.getScheduler() == owner.getScheduler(),
                "Synthetic player must retain the actual native owner scheduler");
        require(Bukkit.isOwnedByCurrentRegion(player),
                "Native ownership check must recognize the owner-backed player accessor");
        proof.put("player_accessor_native_handle_matches_owner", true);
        proof.put("player_accessor_scheduler_matches_owner", true);
        proof.put("player_accessor_owned", true);
        BossBar bossBar = (BossBar) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{BossBar.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setVisible", "setTitle", "setColor", "removeAll" -> null;
                    case "toString" -> "HUD fixture display sink";
                    default -> throw new AssertionError("Unexpected bossbar access: " + method.getName());
                });
        PlayerWAILA updater = (PlayerWAILA) unsafe.allocateInstance(PlayerWAILA.class);
        set(updater, "player", player);
        set(updater, "bossBar", bossBar);
        set(updater, "requestSequence", new AtomicLong());
        set(updater, "displayMode", DisplayMode.BOSSBAR);
        for (String field : new String[]{"facing", "facingBlock", "facingBlockInfo", "lastFailure"}) {
            set(updater, field, "");
        }
        return updater;
    }

    /**
     * Only the accessor boundary is synthetic. The server's unmodified
     * CraftEntity ownership check sees the real pig handle. No player is
     * registered, no connection is created, and Bukkit's server is not replaced.
     */
    private static final class OwnerBackedPlayer extends CraftPlayer {
        private Pig owner;
        private SlimeHudRegionProbe probe;
        private UUID playerId;
        private PlayerInventory inventory;
        private Player.Spigot messages;

        private OwnerBackedPlayer() {
            super(null, null); // Never invoked: this test-only accessor is allocated above.
        }

        @Override public boolean isOnline() { return true; }
        @Override public boolean isValid() { return owner.isValid(); }
        @Override public UUID getUniqueId() { return playerId; }
        @Override public String getName() { return "SlimeHUDRegionFixture"; }
        @Override public Location getEyeLocation() { return probe.eye(); }
        @Override public World getWorld() { return owner.getWorld(); }
        @Override public Location getLocation() { return owner.getLocation(); }
        @Override public PlayerInventory getInventory() { return inventory; }
        @Override public Player.Spigot spigot() { return messages; }
        @Override public net.minecraft.world.entity.Entity getHandleRaw() {
            return ((CraftEntity) owner).getHandleRaw();
        }
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private synchronized void fail(Throwable failure) {
        proof.put("status", "FAIL");
        proof.put("failure", failure.toString());
        try { save(); } catch (Exception ignored) { }
        getLogger().log(java.util.logging.Level.SEVERE, "SLIMEHUD_REGION_PROBE_FAIL", failure);
    }

    private synchronized void save() throws Exception {
        writeReceipt("proof.json", proof);
    }

    private Map<String, Object> receiptIdentity() throws Exception {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schema", 2);
        identity.put("mode", expectedMode);
        identity.put("boot_token", bootToken);
        identity.put("source_commit", sourceCommit);
        Path plugins = getDataFolder().toPath().getParent();
        String addonHash = sha256(plugins.resolve("SlimeHUD.jar"));
        String coreHash = sha256(plugins.resolve("Slimefun.jar"));
        require(addonHash.equals(expectedAddonHash) && coreHash.equals(expectedCoreHash), "Installed JARs differ from the pre-bound request");
        identity.put("installed_addon_sha256", addonHash);
        identity.put("installed_core_sha256", coreHash);
        return identity;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private synchronized void writeReceipt(String name, Map<String, Object> data) throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        Path target = getDataFolder().toPath().resolve(name);
        Path staging = target.resolveSibling(name + ".tmp");
        Files.writeString(staging, new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n");
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
