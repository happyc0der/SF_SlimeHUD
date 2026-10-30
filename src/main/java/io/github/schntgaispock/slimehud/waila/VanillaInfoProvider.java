package io.github.schntgaispock.slimehud.waila;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public final class VanillaInfoProvider {

    private static final String PICKAXE_OF_CONTAINMENT_ID = "PICKAXE_OF_CONTAINMENT";

    private VanillaInfoProvider() {}

    private record ToolHint(String key, String label) {}

    public static String getName(Block block) {
        return humanize(block.getType().name());
    }

    public static String getName(Block block, FileConfiguration config) {
        return getName(block) + getToolSymbolSuffix(block, config);
    }

    public static String getToolSymbolSuffix(Block block, FileConfiguration config) {
        if (!config.getBoolean("vanilla.show-tool-symbol", true)) {
            return "";
        }

        ToolHint hint = preferredTool(block.getType());
        if (hint == null) {
            return "";
        }

        String defaultSymbol = switch (hint.key()) {
            case "pickaxe" -> "⛏";
            case "axe" -> "🪓";
            case "shovel" -> "🪏";
            case "hoe" -> "⚒";
            case "containment" -> "⛏";
            default -> "";
        };
        String symbol = config.getString("vanilla.tool-symbols." + hint.key(), defaultSymbol);
        if (symbol == null || symbol.isBlank()) {
            return "";
        }

        return " &8[&f" + symbol + "&8]";
    }

    public static String getToolInfo(Block block, ItemStack heldItem, FileConfiguration config) {
        if (!config.getBoolean("vanilla.show-tool", true)) {
            return "";
        }

        ToolHint hint = preferredTool(block.getType());
        if (hint == null) {
            return "";
        }

        String tool = hint.label();
        if (heldItem != null && !heldItem.getType().isAir()) {
            tool += isCorrectHeldTool(block, heldItem, hint) ? " (held ✓)" : " (held ✗)";
        }
        return "Tool: " + tool;
    }

    public static boolean isContainmentSpawner(SlimefunItem slimefunItem, Block block) {
        if (block.getType() != Material.SPAWNER) {
            return false;
        }
        return slimefunItem == null || "REPAIRED_SPAWNER".equals(slimefunItem.getId());
    }

    public static String getInfo(Block block, ItemStack heldItem, FileConfiguration config) {
        List<String> parts = new ArrayList<>();
        BlockData data = block.getBlockData();

        if (config.getBoolean("vanilla.show-redstone", true)) {
            if (data instanceof AnaloguePowerable analogue) {
                parts.add("Power: " + analogue.getPower() + "/" + analogue.getMaximumPower());
            } else if (data instanceof Powerable powerable) {
                parts.add(powerable.isPowered() ? "Powered" : "Unpowered");
            }
        }

        if (config.getBoolean("vanilla.show-crop-age", true) && data instanceof Ageable ageable) {
            parts.add("Age: " + ageable.getAge() + "/" + ageable.getMaximumAge());
        }

        if (config.getBoolean("vanilla.show-beehive", true) && data instanceof Beehive beehive) {
            parts.add("Honey: " + beehive.getHoneyLevel() + "/" + beehive.getMaximumHoneyLevel());
        }

        if (config.getBoolean("vanilla.show-farmland", true) && data instanceof Farmland farmland) {
            parts.add(farmland.getMoisture() > 0
                    ? "Hydrated " + farmland.getMoisture() + "/" + farmland.getMaximumMoisture()
                    : "Dry farmland");
        }

        if (config.getBoolean("vanilla.show-note-block", true) && data instanceof NoteBlock noteBlock) {
            parts.add("Note: " + humanize(noteBlock.getInstrument().name()) + " " + noteBlock.getNote());
        }

        if (config.getBoolean("vanilla.show-waterlogged", false) && data instanceof Waterlogged waterlogged) {
            parts.add(waterlogged.isWaterlogged() ? "Waterlogged" : "Dry");
        }

        if (config.getBoolean("vanilla.show-open-state", false) && data instanceof Openable openable) {
            parts.add(openable.isOpen() ? "Open" : "Closed");
        }

        if (config.getBoolean("vanilla.show-lit-state", false) && data instanceof Lightable lightable) {
            parts.add(lightable.isLit() ? "Lit" : "Unlit");
        }

        BlockState state = block.getState();

        if (config.getBoolean("vanilla.show-smelting", true) && state instanceof Furnace furnace) {
            if (furnace.getCookTimeTotal() > 0) {
                parts.add("Smelt: " + furnace.getCookTime() + "/" + furnace.getCookTimeTotal());
            }
            if (furnace.getBurnTime() > 0) {
                parts.add("Fuel: " + furnace.getBurnTime() + "t");
            }
        }

        if (config.getBoolean("vanilla.show-brewing", true) && state instanceof BrewingStand brewingStand) {
            if (brewingStand.getBrewingTime() > 0) {
                parts.add("Brew: " + brewingStand.getBrewingTime() + "t");
            }
            if (brewingStand.getFuelLevel() > 0) {
                parts.add("Fuel: " + brewingStand.getFuelLevel());
            }
        }

        if (config.getBoolean("vanilla.show-container-items", true) && state instanceof Container container) {
            int itemCount = 0;
            int occupiedSlots = 0;
            for (ItemStack stack : container.getInventory().getStorageContents()) {
                if (stack != null && !stack.getType().isAir()) {
                    occupiedSlots++;
                    itemCount += stack.getAmount();
                }
            }
            if (occupiedSlots > 0) {
                parts.add("Contents: " + itemCount + " items / " + occupiedSlots + " slots");
            } else {
                parts.add("Empty");
            }
        }

        if (config.getBoolean("vanilla.show-spawner-type", true) && state instanceof CreatureSpawner spawner) {
            if (spawner.getSpawnedType() != null) {
                parts.add("Spawner: " + humanize(spawner.getSpawnedType().name()));
            }
        }

        if (config.getBoolean("vanilla.show-beacon-effects", true) && state instanceof Beacon beacon) {
            PotionEffect primary = beacon.getPrimaryEffect();
            PotionEffect secondary = beacon.getSecondaryEffect();
            if (primary != null) {
                parts.add("Primary: " + humanize(primary.getType().getKey().getKey()));
            }
            if (secondary != null) {
                parts.add("Secondary: " + humanize(secondary.getType().getKey().getKey()));
            }
        }

        String toolInfo = getToolInfo(block, heldItem, config);
        if (!toolInfo.isEmpty()) {
            parts.add(toolInfo);
        }

        return String.join(" &8| &7", parts);
    }

    private static ToolHint preferredTool(Material material) {
        if (material == Material.SPAWNER) {
            return new ToolHint("containment", "Pickaxe of Containment");
        }

        String key;
        String label;
        if (Tag.MINEABLE_PICKAXE.isTagged(material)) {
            key = "pickaxe";
            label = "Pickaxe";
        } else if (Tag.MINEABLE_AXE.isTagged(material)) {
            key = "axe";
            label = "Axe";
        } else if (Tag.MINEABLE_SHOVEL.isTagged(material)) {
            key = "shovel";
            label = "Shovel";
        } else if (Tag.MINEABLE_HOE.isTagged(material)) {
            key = "hoe";
            label = "Hoe";
        } else {
            return null;
        }

        if (Tag.NEEDS_DIAMOND_TOOL.isTagged(material)) {
            label += " (Diamond+)";
        } else if (Tag.NEEDS_IRON_TOOL.isTagged(material)) {
            label += " (Iron+)";
        } else if (Tag.NEEDS_STONE_TOOL.isTagged(material)) {
            label += " (Stone+)";
        }
        return new ToolHint(key, label);
    }

    private static boolean isCorrectHeldTool(Block block, ItemStack heldItem, ToolHint hint) {
        if (block.getType() == Material.SPAWNER) {
            SlimefunItem slimefunItem = SlimefunItem.getByItem(heldItem);
            return slimefunItem != null && PICKAXE_OF_CONTAINMENT_ID.equals(slimefunItem.getId());
        }
        // Block#isPreferredTool is true for ANY item on blocks that need no tool (grass, dirt...),
        // so also require the held item to belong to the tool family the hint names.
        Material held = heldItem.getType();
        boolean family = switch (hint.key()) {
            case "pickaxe" -> Tag.ITEMS_PICKAXES.isTagged(held);
            case "axe" -> Tag.ITEMS_AXES.isTagged(held);
            case "shovel" -> Tag.ITEMS_SHOVELS.isTagged(held);
            case "hoe" -> Tag.ITEMS_HOES.isTagged(held);
            default -> true;
        };
        return family && block.isPreferredTool(heldItem);
    }

    private static String humanize(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
