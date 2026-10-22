package verification;

import io.github.schntgaispock.slimehud.SlimeHUD;
import io.github.schntgaispock.slimehud.waila.PlayerWAILA;
import io.github.schntgaispock.slimehud.waila.WAILAManager;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class SlimeHudClientProbe extends JavaPlugin {
    private Vector aim = new Vector(0.5, 101.5, 4.5);
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length==0) return false;
        Player player=Bukkit.getPlayerExact("HudProbe");
        if(player==null){getLogger().severe("HUDVERIFY_FAIL player missing");return true;}
        try {
            World world=player.getWorld();
            switch(args[0]) {
                case "target" -> {
                    for(Item item: world.getEntitiesByClass(Item.class)) item.remove();
                    for(int x=-2;x<=2;x++) for(int y=100;y<=104;y++) for(int z=1;z<=7;z++) {
                        Block block=world.getBlockAt(x,y,z); if(BlockStorage.hasBlockInfo(block)) BlockStorage.clearBlockInfo(block); block.setType(Material.AIR,false);
                    }
                    for(int x=-2;x<=2;x++) for(int z=-2;z<=7;z++) world.getBlockAt(x,99,z).setType(Material.STONE,false);
                    Block target=world.getBlockAt(0,101,4); world.getBlockAt(0,100,4).setType(Material.STONE,false);
                    if(args[1].startsWith("SF:")) {
                        SlimefunItem item=SlimefunItem.getById(args[1].substring(3));
                        if(item==null) throw new IllegalArgumentException("Unknown Slimefun id: "+args[1]);
                        target.setType(item.getItem().getType(),false);BlockStorage.store(target,item.getId());
                        aim=target.getBoundingBox().getCenter();
                    } else if(args[1].equals("DROP")) {
                        world.getBlockAt(0,100,4).setType(Material.AIR,false);
                        Item item=world.dropItem(new Location(world,.5,101.3,4.5),new ItemStack(Material.DIAMOND,12));
                        item.setGravity(false);item.setVelocity(new Vector());item.setPickupDelay(Integer.MAX_VALUE);
                        aim=item.getLocation().toVector().add(new Vector(0,.1,0));
                    } else {
                        Material mat=Material.valueOf(args[1]);
                        if(mat==Material.WHEAT)world.getBlockAt(0,100,4).setType(Material.FARMLAND,false);
                        if(mat==Material.REDSTONE_WIRE)world.getBlockAt(1,101,4).setType(Material.REDSTONE_BLOCK,false);
                        target.setType(mat,false);
                        if(target.getBlockData() instanceof Ageable age){age.setAge(Integer.parseInt(args[2]));target.setBlockData(age,false);}
                        aim=target.getBoundingBox().getCenter();
                    }
                    orient(player);
                }
                case "aim" -> orient(player);
                case "away" -> player.teleport(new Location(world,.5,100,.5,180,-25));
                case "rate" -> {SlimeHUD.getInstance().getConfig().set("waila.tick-rate",Integer.parseInt(args[1]));WAILAManager.getInstance().reloadAll();}
                case "state" -> {
                    PlayerWAILA w=WAILAManager.getInstance().getWaila(player);
                    getLogger().info("HUDVERIFY_STATE "+args[1]+" sneaking="+player.isSneaking()+" target="+(w==null?"(missing)":w.getFacingBlock())+" info="+(w==null?"":w.getFacingBlockInfo())+" error="+(w==null?"":w.getLastFailure()));
                }
                default -> throw new IllegalArgumentException("Unknown action "+args[0]);
            }
            getLogger().info("HUDVERIFY_OK "+String.join(" ",args));
        }catch(Throwable t){getLogger().log(java.util.logging.Level.SEVERE,"HUDVERIFY_FAIL "+String.join(" ",args),t);}
        return true;
    }
    private void orient(Player player) {
        double eyeHeight=player.isSneaking()?1.27:1.62;
        Vector dir=aim.clone().subtract(new Vector(.5,100+eyeHeight,.5));
        Location l=new Location(player.getWorld(),.5,100,.5);l.setDirection(dir);player.teleport(l);
    }
}
