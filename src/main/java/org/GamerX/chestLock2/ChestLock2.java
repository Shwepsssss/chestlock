package org.GamerX.chestLock2;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.xml.stream.FactoryConfigurationError;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.block.BlockBreakEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.World;

public class ChestLock2 extends JavaPlugin implements Listener {
    static class BrokenChestData {
        String world;
        int x, y, z;
        Material type;
        ItemStack[] contents;
        String owner;
    }

    private final Map<UUID, BrokenChestData> brokenChests = new HashMap<>();

    private final Map<String, String> chestOwners = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        chestOwners.clear();
        loadOwners();
        getServer().getPluginManager().registerEvents(this, this);
    }


    @Override
    public void onDisable() {
        saveOwners();
    }









    @EventHandler
    public void onTamedPetDamage(EntityDamageByEntityEvent event) {

        Player player = null;

        // Direct melee
        if (event.getDamager() instanceof Player p) {
            player = p;
        }

        // Projectile (arrow / trident)
        if (event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player p) {
                player = p;
            }
        }

        if (player == null) return;

        // OPs are allowed
        if (player.isOp()) return;

        Entity entity = event.getEntity();

        boolean isProtectedPet = false;

        // Wolves, Cats, Parrots, etc.
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            isProtectedPet = true;
        }

        // Horses, Donkeys, Llamas, Camels
        if (entity instanceof AbstractHorse horse && horse.isTamed()) {
            isProtectedPet = true;
        }

        // Happy Ghast (safe for newer versions)
        if (entity.getType().name().equals("HAPPY_GHAST")) {
            isProtectedPet = true;
        }

        if (!isProtectedPet) return;

        // Cancel damage
        event.setCancelled(true);

        // Remove fire if applied (Fire Aspect / Flame)
        entity.setFireTicks(0);

        player.sendMessage(ChatColor.RED + "אין לך אפשרות לפגוע או להצית חיות מבויתות.");
    }

    @EventHandler
    public void onTamedPetEnvironmentalDamage(EntityDamageEvent event) {

        Entity entity = event.getEntity();

        boolean isProtectedPet = false;

        // Tameable animals (wolf, cat, parrot, etc.)
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            isProtectedPet = true;
        }

        // Horses, llamas, donkeys, camels
        if (entity instanceof AbstractHorse horse && horse.isTamed()) {
            isProtectedPet = true;
        }

        // Happy Ghast (future-safe)
        if (entity.getType().name().equals("HAPPY_GHAST")) {
            isProtectedPet = true;
        }

        if (!isProtectedPet) return;

        EntityDamageEvent.DamageCause cause = event.getCause();

        // Cancel ALL fire & lava damage
        if (cause == EntityDamageEvent.DamageCause.FIRE ||
                cause == EntityDamageEvent.DamageCause.FIRE_TICK ||
                cause == EntityDamageEvent.DamageCause.LAVA) {

            event.setCancelled(true);
            entity.setFireTicks(0);
        }
    }




    private String locKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private void loadOwners() {
        if (!getConfig().isConfigurationSection("chests")) {
            // Create empty section so it doesn't error
            getConfig().createSection("chests");
            saveConfig();
            return;
        }

        ConfigurationSection section = getConfig().getConfigurationSection("chests");

        for (String key : section.getKeys(false)) {
            chestOwners.put(key, section.getString(key));
        }
    }


    private void saveOwners() {
        getConfig().set("chests", null); // clear section
        for (Map.Entry<String, String> entry : chestOwners.entrySet()) {
            getConfig().set("chests." + entry.getKey(), entry.getValue());
        }
        saveConfig();
    }


    // 🔎 Find if the chest connects to an existing chest
    private Block getConnectedChest(Block placed) {
        Block[] neighbors = new Block[]{
                placed.getRelative(1, 0, 0),
                placed.getRelative(-1, 0, 0),
                placed.getRelative(0, 0, 1),
                placed.getRelative(0, 0, -1)
        };

        for (Block b : neighbors) {
            if (b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) {
                return b;
            }
        }

        return null;
    }

    @EventHandler
    public void onChestPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (block.getType() != Material.CHEST) return;

        Block connected = getConnectedChest(block);

        String ownerName;

        // If connecting to another chest → adopt its owner
        if (connected != null) {
            String key = locKey(connected);

            // If the connected chest was owned → inherit owner
            if (chestOwners.containsKey(key)) {
                ownerName = chestOwners.get(key);
            } else {
                // If it wasn't owned (existing chest from before plugin) → protect under that placer
                ownerName = event.getPlayer().getName();
            }
        } else {
            // Single chest placed → owner = placer
            ownerName = event.getPlayer().getName();
        }

        // Save ownership for placed chest
        chestOwners.put(locKey(block), ownerName);

        // If forming double chest, also force owner on the other block
        if (connected != null) {
            chestOwners.put(locKey(connected), ownerName);
        }

        event.getPlayer().sendMessage(ChatColor.GREEN + "תיבה זאת נעולה לשימוש שלך");

        saveOwners();
    }

    @EventHandler
    public void onChestOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();

        // ✅ Correct double chest detection
        if (holder instanceof DoubleChest doubleChest) {

            Chest left = (Chest) doubleChest.getLeftSide();
            Chest right = (Chest) doubleChest.getRightSide();

            String keyLeft = locKey(left.getBlock());
            String keyRight = locKey(right.getBlock());

            // Get owner (left or right)
            String owner = chestOwners.get(keyLeft);
            if (owner == null) owner = chestOwners.get(keyRight);

            if (owner == null) return; // unowned chest

            if (event.getPlayer().isOp()) return;

            if (!event.getPlayer().getName().equals(owner)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "את/ה לא שייך לתיבה זאת");
            }

            return;
        }

        // ✅ Single chest
        if (holder instanceof Chest chest) {
            String key = locKey(chest.getBlock());

            if (!chestOwners.containsKey(key)) return;
            if (event.getPlayer().isOp()) return;

            if (!event.getPlayer().getName().equals(chestOwners.get(key))) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "את/ה לא שייך לתיבה זאת");
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block ->
                (block.getType() == Material.CHEST)
                        && isLocked(block)
        );
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block ->
                (block.getType() == Material.CHEST)
                        && isLocked(block)
        );
    }

    private boolean isLocked(Block block) {
        return chestOwners.containsKey(locKey(block));
    }



    private void applyEffectToAll(PotionEffectType type) {
        PotionEffect effect = new PotionEffect(
                type,
                Integer.MAX_VALUE, // infinite duration
                0,                 // amplifier level 1
                false,
                false
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.addPotionEffect(effect);
        }
    }

    private void applyTimedEffect(PotionEffectType type, int durationTicks, int amplifier) {
        PotionEffect effect = new PotionEffect(
                type,
                durationTicks,
                amplifier,
                false,
                true
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.addPotionEffect(effect);
        }
    }


    @EventHandler
    public void onChestBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Chest chest) || event.getBlock().getType() != Material.CHEST) return;
        String playerBreakName = event.getPlayer().getName();
        Block block = chest.getBlock();
        World world = block.getWorld();



        // 📦 Capture FULL inventory (single + double chest)
        ItemStack[] original = chest.getInventory().getContents();
        ItemStack[] contents = new ItemStack[original.length];

        for (int i = 0; i < original.length; i++) {
            if (original[i] != null) {
                contents[i] = original[i].clone(); // 🔥 deep clone
            }
        }

        // 🧠 Save restore data
        BrokenChestData data = new BrokenChestData();
        data.world = world.getName();
        data.x = block.getX();
        data.y = block.getY();
        data.z = block.getZ();
        data.type = block.getType();
        data.contents = contents;
        data.owner = chestOwners.get(locKey(block));
        UUID restoreId = UUID.randomUUID();
        brokenChests.put(restoreId, data);

        sendRestoreClickableToOps(data, restoreId, playerBreakName);
    }



    private void sendRestoreClickableToOps(BrokenChestData data, UUID id, String name) {
        TextComponent base = new TextComponent(
                ChatColor.YELLOW + "[ChestBreak] "
                        + ChatColor.WHITE + data.world + " "
                        + ChatColor.AQUA + data.x + "," + data.y + "," + data.z
                        + ChatColor.WHITE +" Broke the chest: "
                        + ChatColor.DARK_PURPLE + " "+ name
                        + ChatColor.WHITE + " Owner: "
                        + ChatColor.RED + (data.owner != null ? data.owner : "GLOBAL") + " "
        );

        TextComponent restore = new TextComponent(ChatColor.GREEN + "[RESTORE]");
        restore.setBold(true);
        restore.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/__restore_internal " + id
        ));
        restore.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Click to restore this chest").create()
        ));

        base.addExtra(restore);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                p.spigot().sendMessage(base);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("__restore_internal")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.isOp()) return true;


            UUID id;
            try {
                id = UUID.fromString(args[0]);
            } catch (Exception e) {
                return true;
            }

            BrokenChestData data = brokenChests.remove(id);

            if (data == null) {
                p.sendMessage(ChatColor.RED + "Restore expired or already used.");
                return true;
            }

            World world = Bukkit.getWorld(data.world);
            if (world == null) return true;

            Block block = world.getBlockAt(data.x, data.y, data.z);
            if (!block.getType().isAir()) {
                p.sendMessage(ChatColor.RED + "Cannot restore: block is not empty.");
                return true;
            }

            block.setType(Material.CHEST, false);
            Chest c = (Chest) block.getState();


            Inventory inv = c.getInventory();
            for(ItemStack item : data.contents){
                if (item != null){
                inv.addItem((ItemStack) item.clone());
                }
            }


            if (data.owner != null) {
                chestOwners.put(locKey(block), data.owner);
                saveOwners();
            }

            p.sendMessage(ChatColor.GREEN + "Chest restored successfully.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("claim")) {
            if (!(sender instanceof Player p)) return false;

            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "אין לך גישה לפקודה הזאת.");
                return false;
            }

            Block targetBlock = p.getTargetBlock(Set.of(Material.AIR), 10);

            if (targetBlock.getType() != Material.CHEST) {
                p.sendMessage(ChatColor.RED + "אתה לא מסתכל על תיבה.");
                return true;
            }

            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "עליך להשתמש בפקודה:");
                p.sendMessage(ChatColor.RED + "/claim [USERNAME]");
                return true;
            }

            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "שחקן זה לא אונליין.");
                return true;
            }

            String key = locKey(targetBlock);

            chestOwners.put(key, target.getName());
            saveOwners();


            p.sendMessage(ChatColor.GREEN + "התיבה שויכה ל־ " + target.getName());
            target.sendMessage(ChatColor.GREEN + "תיבה חדשה שויכה אליך");

            return true;
        }

        if (command.getName().equalsIgnoreCase("global")) {
            if (!(sender instanceof Player p)) return false;

            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "אין לך גישה לפקודה הזאת.");
                return false;
            }

            Block targetBlock = p.getTargetBlock(Set.of(Material.AIR), 10);

            if (targetBlock.getType() != Material.CHEST && targetBlock.getType() != Material.TRAPPED_CHEST) {
                p.sendMessage(ChatColor.RED + "אתה לא מסתכל על תיבה.");
                return true;
            }

            String key = locKey(targetBlock);

            chestOwners.remove(key);
            saveOwners();


            p.sendMessage(ChatColor.GREEN + "התיבה עכשיו גלובלית.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("who")) {
            if (!(sender instanceof Player p)) return false;

            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "אין לך גישה לפקודה הזאת.");
                return false;
            }

            Block targetBlock = p.getTargetBlock(Set.of(Material.AIR), 10);

            if (targetBlock.getType() != Material.CHEST && targetBlock.getType() != Material.TRAPPED_CHEST) {
                p.sendMessage(ChatColor.RED + "אתה לא מסתכל על תיבה.");
                return true;
            }

            String key = locKey(targetBlock);

            String owner = chestOwners.get(key);

            p.sendMessage(ChatColor.GREEN + "התיבה שייכת ל- " + owner);
            return true;
        }


        if (command.getName().equalsIgnoreCase("nv")) {
            applyEffectToAll(PotionEffectType.NIGHT_VISION);
            sender.sendMessage("§aNight Vision enabled for everyone.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("fr")) {
            applyEffectToAll(PotionEffectType.FIRE_RESISTANCE);
            sender.sendMessage("§cFire Resistance enabled for everyone.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("reg")) {
            applyTimedEffect(
                    PotionEffectType.REGENERATION,
                    30 * 60 * 20, // 30 minutes in ticks
                    2             // amplifier 2 = Regeneration III
            );
            sender.sendMessage("§dRegeneration III enabled for 30 minutes.");

        }
            return false;
    }
}