package io.github.schntgaispock.slimehud.probe;

import com.google.gson.GsonBuilder;
import io.github.schntgaispock.slimehud.waila.DisplayMode;
import io.github.schntgaispock.slimehud.waila.PlayerWAILA;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
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
 * it delegates location and scheduling to an actual Folia-owned pig, while native
 * World/Block/Entity APIs and the installed SlimeHUD updater remain unmodified.
 */
public final class SlimeHudRegionProbe extends JavaPlugin {
    private final Map<String, Object> proof = new LinkedHashMap<>();
    private boolean started;
    private Pig owner;
    private PlayerWAILA hud;
    private Location nearbyBlock;
    private Item nearbyItem;
    private boolean expectFailure;
    private boolean singleRegion;
    private int crossingDistance;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (started || args.length != 1 || !(args[0].equals("control") || args[0].equals("candidate") || args[0].equals("single-region"))) {
            return false;
        }
        started = true;
        expectFailure = args[0].equals("control");
        singleRegion = args[0].equals("single-region");
        proof.put("mode", args[0]);
        proof.put("connected_player", false);
        proof.put("fixture", "Synthetic player accessors backed by a native entity owner; real production HUD updater");
        proof.put("server", Bukkit.getVersion());
        proof.put("java", System.getProperty("java.version"));
        proof.put("slimehud_version", Bukkit.getPluginManager().getPlugin("SlimeHUD").getPluginMeta().getVersion());
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
            hud.run();
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
            hud.run();
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
                require(failure.startsWith("native HUD update:"), "Failure came from a different HUD phase");
                require(failure.toLowerCase().matches(".*(thread|region|chunk).*"), "Failure was not an ownership/chunk-thread diagnostic: " + failure);
            } else {
                require(failure.isEmpty(), "Candidate queried an unsafe native ray: " + failure);
                require(hud.getFacingBlock().isEmpty(), "Empty ray must not retain a stale block label");
            }
            proof.put("crossing_outcome_matches_expectation", true);
            long previousFailure = hud.getLastFailureEpochMillis();
            proof.put("last_failure_before_recovery", previousFailure);
            nearbyBlock.getBlock().setType(Material.STONE);
            hud.setMaxDistanceOverride(8);
            hud.run();
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
            hud.run();
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
            hud.run();
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
            finish();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void finish() throws Exception {
        proof.put("status", "PASS");
        hud.stop();
        nearbyBlock.getBlock().setType(Material.AIR);
        if (nearbyItem != null) nearbyItem.remove();
        owner.remove();
        save();
        getLogger().info("SLIMEHUD_REGION_PROBE_PASS " + (expectFailure ? "control" : "candidate"));
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
        Player player = (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "isOnline", "isValid" -> true;
                    case "getUniqueId" -> playerId;
                    case "getName" -> "SlimeHUDRegionFixture";
                    case "getEyeLocation" -> eye();
                    case "getWorld" -> owner.getWorld();
                    case "getLocation" -> owner.getLocation();
                    case "getScheduler" -> owner.getScheduler();
                    case "getInventory" -> inventory;
                    case "spigot" -> spigot;
                    case "hashCode" -> playerId.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "SlimeHUD native owner accessor fixture";
                    default -> throw new AssertionError("Unexpected player access: " + method.getName());
                });
        BossBar bossBar = (BossBar) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{BossBar.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setVisible", "setTitle", "setColor", "removeAll" -> null;
                    case "toString" -> "HUD fixture display sink";
                    default -> throw new AssertionError("Unexpected bossbar access: " + method.getName());
                });
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
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
        Files.createDirectories(getDataFolder().toPath());
        Files.writeString(getDataFolder().toPath().resolve("proof.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof) + "\n");
    }
}
