package io.github.schntgaispock.slimehud.waila;

import io.github.schntgaispock.slimehud.SlimeHUD;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

public final class WAILAManager implements Listener {

    private static WAILAManager instance;

    private final Map<UUID, PlayerWAILA> wailas = new ConcurrentHashMap<>();

    private WAILAManager() {}

    public static WAILAManager getInstance() {
        if (instance == null) {
            instance = new WAILAManager();
        }
        return instance;
    }

    public static void setup() {
        WAILAManager manager = getInstance();
        Bukkit.getPluginManager().registerEvents(manager, SlimeHUD.getInstance());
        for (Player player : Bukkit.getOnlinePlayers()) {
            manager.refreshPlayer(player);
        }
    }

    public static void shutdown() {
        if (instance == null) {
            return;
        }
        for (PlayerWAILA waila : instance.wailas.values()) {
            waila.stop();
        }
        instance.wailas.clear();
        instance = null;
    }

    public Map<UUID, PlayerWAILA> getWailas() {
        return Collections.unmodifiableMap(wailas);
    }

    public PlayerWAILA getWaila(Player player) {
        return wailas.get(player.getUniqueId());
    }

    /**
     * Players must explicitly opt in. Existing choices in player.yml remain
     * authoritative; a missing key uses the configurable default (off).
     */
    public static boolean isEnabledForPlayer(UUID uuid) {
        SlimeHUD plugin = SlimeHUD.getInstance();
        return plugin.getPlayerData().getBoolean(
                uuid + ".waila", plugin.getConfig().getBoolean("waila.default-enabled", false));
    }

    public void refreshPlayer(Player player) {
        if (SlimeHUD.getInstance().getConfig().getBoolean("waila.disabled", false) || isDisabledWorld(player)) {
            PlayerWAILA existing = wailas.get(player.getUniqueId());
            if (existing != null) {
                existing.setPaused(true);
            }
            return;
        }

        // Do not allocate a BossBar or schedule a repeating task for players
        // who have not opted in. Opting out stops and removes an existing HUD.
        if (!isEnabledForPlayer(player.getUniqueId())) {
            removeWAILA(player);
            return;
        }

        PlayerWAILA waila = wailas.computeIfAbsent(player.getUniqueId(), ignored -> {
            PlayerWAILA created = new PlayerWAILA(player);
            created.start();
            return created;
        });
        waila.setPaused(false);

        String defaultDisplay = SlimeHUD.getInstance().getConfig().getString(
                "waila.default-display",
                SlimeHUD.getInstance().getConfig().getString("waila.location", "bossbar"));
        String storedDisplay = SlimeHUD.getInstance().getPlayerData().getString(
                player.getUniqueId() + ".display", defaultDisplay);
        waila.setDisplayMode(DisplayMode.from(storedDisplay, DisplayMode.BOSSBAR));
    }

    public void reloadAll() {
        for (PlayerWAILA waila : wailas.values()) {
            waila.stop();
        }
        wailas.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    private boolean isDisabledWorld(Player player) {
        return SlimeHUD.getInstance()
                .getConfig()
                .getStringList("waila.disabled-in")
                .stream()
                .anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName())
                        || world.equalsIgnoreCase(player.getWorld().getKey().toString()));
    }

    private void removeWAILA(Player player) {
        PlayerWAILA waila = wailas.remove(player.getUniqueId());
        if (waila != null) {
            waila.stop();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeWAILA(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        PlayerWAILA waila = wailas.get(event.getPlayer().getUniqueId());
        if (waila != null) {
            waila.onSneakChanged(event.isSneaking());
        }
    }
}
