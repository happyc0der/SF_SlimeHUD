package io.github.schntgaispock.slimehud.placeholder;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.waila.DisplayMode;
import io.github.schntgaispock.slimehud.waila.PlayerWAILA;
import io.github.schntgaispock.slimehud.waila.WAILAManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public final class PlaceholderHook extends PlaceholderExpansion {

    @Override
    public String getIdentifier() {
        return "slimehud";
    }

    @Override
    public String getAuthor() {
        return "wickidcow";
    }

    @Override
    public String getVersion() {
        return SlimeHUD.getInstance().getPluginVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        }

        if (params.equalsIgnoreCase("toggle")) {
            return Boolean.toString(WAILAManager.isEnabledForPlayer(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("display")) {
            String defaultDisplay = SlimeHUD.getInstance().getConfig().getString(
                    "waila.default-display",
                    SlimeHUD.getInstance().getConfig().getString("waila.location", "bossbar"));
            return DisplayMode.from(
                            SlimeHUD.getInstance().getPlayerData().getString(
                                    player.getUniqueId() + ".display", defaultDisplay),
                            DisplayMode.BOSSBAR)
                    .id();
        }

        PlayerWAILA waila = WAILAManager.getInstance().getWaila(player);
        if (waila == null) {
            return "";
        }

        return switch (params.toLowerCase()) {
            case "hud" -> waila.getFacing();
            case "hud_block" -> waila.getFacingBlock();
            case "hud_info" -> waila.getFacingBlockInfo();
            default -> null;
        };
    }
}
