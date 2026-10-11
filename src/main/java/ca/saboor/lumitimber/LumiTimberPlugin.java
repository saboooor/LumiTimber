package ca.saboor.lumitimber;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LumiTimberPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final TreeDetector detector = new TreeDetector();
    private Settings settings;
    private PlacedBlocks placed;
    private TimberAnimation animation;
    private NamespacedKey toggleKey;
    private NamespacedKey lastChopKey;
    private final Set<Location> protectedSaplings = ConcurrentHashMap.newKeySet();
    private boolean probing;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = Settings.load(getConfig());
        } catch (IllegalArgumentException | NullPointerException e) {
            getLogger().severe("Invalid configuration: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        placed = new PlacedBlocks(this);
        toggleKey = new NamespacedKey(this, "disabled");
        lastChopKey = new NamespacedKey(this, "last_chop");
        animation = new TimberAnimation(this);

        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("timber");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Clean any stray display entities in currently loaded chunks (e.g. from an ungraceful server kill)
        for (World world : getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                animation.clean(chunk);
            }
        }
    }

    @Override
    public void onDisable() {
        if (animation != null) {
            animation.close();
        }
        protectedSaplings.clear();
    }

    private boolean isTreeMaterial(Block block) {
        String name = block.getType().name();
        for (TreeDetector.Definition t : settings.trees()) {
            if (t.logs().contains(name) || t.leaves().contains(name)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isTreeMaterial(event.getBlockPlaced())) {
            placed.set(event.getBlockPlaced(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOrdinaryBreak(BlockBreakEvent event) {
        if (!probing) {
            placed.set(event.getBlock(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            if (isTreeMaterial(b)) {
                placed.set(b, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            if (isTreeMaterial(b)) {
                placed.set(b, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        movePistonMarkers(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        movePistonMarkers(event.getBlocks(), event.getDirection());
    }

    private void movePistonMarkers(List<Block> blocks, BlockFace direction) {
        Map<Block, Boolean> markers = new LinkedHashMap<>();
        for (Block b : blocks) {
            markers.put(b, placed.contains(b));
        }
        getServer().getScheduler().runTask(this, () -> {
            for (var entry : markers.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    Block source = entry.getKey();
                    Block target = source.getRelative(direction);
                    if (target.getWorld().isChunkLoaded(target.getX() >> 4, target.getZ() >> 4) && isTreeMaterial(target)) {
                        placed.set(target, true);
                    }
                    if (!isTreeMaterial(source)) {
                        placed.set(source, false);
                    }
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChop(BlockBreakEvent event) {
        if (probing) return;

        Block initial = event.getBlock();
        Player player = event.getPlayer();

        // Prevent breaking recently replanted saplings during cooldown
        if (protectedSaplings.contains(initial.getLocation())) {
            event.setCancelled(true);
            return;
        }

        // Validate player conditions
        if (!player.hasPermission("lumitimber.chop")) return;
        if (player.getGameMode() != GameMode.SURVIVAL) return;
        if (settings.disabledWorlds().contains(initial.getWorld().getName())) return;
        if (player.getPersistentDataContainer().has(toggleKey)) return;

        // Check topple mode (sneaking requirements)
        Settings.ToppleMode mode = settings.toppleMode();
        if (mode == Settings.ToppleMode.SNEAKING && !player.isSneaking()) return;
        if (mode == Settings.ToppleMode.NOT_SNEAKING && player.isSneaking()) return;

        // Tool requirements
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (settings.requireAxe() && !Tag.ITEMS_AXES.isTagged(tool.getType())) return;

        // Cooldown check
        if (settings.cooldownTicks() > 0 && !player.hasPermission("lumitimber.bypass.cooldown")) {
            long now = System.currentTimeMillis();
            Long last = player.getPersistentDataContainer().get(lastChopKey, PersistentDataType.LONG);
            if (last != null && now - last < settings.cooldownTicks() * 50L) {
                return;
            }
        }

        // Build world access adapter for tree detection
        World world = initial.getWorld();
        TreeDetector.Access access = new TreeDetector.Access() {
            @Override
            public boolean available(TreeDetector.Pos p) {
                return p.y() >= world.getMinHeight() && p.y() < world.getMaxHeight() && world.isChunkLoaded(p.x() >> 4, p.z() >> 4);
            }

            private Block block(TreeDetector.Pos p) {
                return world.getBlockAt(p.x(), p.y(), p.z());
            }

            @Override
            public String material(TreeDetector.Pos p) {
                return block(p).getType().name();
            }

            @Override
            public boolean placed(TreeDetector.Pos p) {
                Block b = block(p);
                return placed.contains(b) || (b.getBlockData() instanceof Leaves leaves && leaves.isPersistent());
            }
        };

        // Detect tree
        TreeDetector.Tree tree = detector.detect(
                access,
                new TreeDetector.Pos(initial.getX(), initial.getY(), initial.getZ()),
                settings.trees(),
                settings.detection()
        );
        if (tree == null) return;

        // Tool durability protection
        int cost = settings.damagePerLog() ? tree.logs().size() : 1;
        if (settings.protectTool() && tool.getItemMeta() instanceof Damageable meta && !meta.isUnbreakable()) {
            int maxDur = tool.getType().getMaxDurability();
            if (maxDur > 0 && meta.getDamage() + cost >= maxDur) {
                player.sendActionBar(Component.text("Your tool would break from timbering this tree!", NamedTextColor.RED));
                return;
            }
        }

        // Snapshot blocks
        List<TreeDetector.Pos> positions = new ArrayList<>(tree.logs());
        positions.addAll(tree.leaves());

        Map<Block, BlockData> snapshots = new LinkedHashMap<>();
        for (TreeDetector.Pos p : positions) {
            Block b = world.getBlockAt(p.x(), p.y(), p.z());
            snapshots.put(b, b.getBlockData().clone());
        }

        // Probe external protection plugins (WorldGuard, GriefPrevention, Towny, CoreProtect, etc.)
        probing = true;
        try {
            for (Block b : snapshots.keySet()) {
                if (!b.equals(initial)) {
                    BlockBreakEvent check = new BlockBreakEvent(b, player);
                    getServer().getPluginManager().callEvent(check);
                    if (check.isCancelled()) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        } finally {
            probing = false;
        }

        // Cancel original vanilla break so we take full control over removal & animation
        event.setCancelled(true);

        // Immediate audio and visual feedback for the broken log
        if (settings.sounds()) {
            var sg = initial.getBlockData().getSoundGroup();
            world.playSound(initial.getLocation().add(0.5, 0.5, 0.5), sg.getBreakSound(), SoundCategory.BLOCKS, sg.getVolume(), sg.getPitch());
        }
        if (settings.particles()) {
            world.spawnParticle(Particle.BLOCK, initial.getLocation().add(0.5, 0.5, 0.5), 16, 0.25, 0.25, 0.25, initial.getBlockData());
        }

        // Gather drops and construct animation pieces
        List<TimberAnimation.Piece> pieces = new ArrayList<>(snapshots.size());
        for (var entry : snapshots.entrySet()) {
            Block b = entry.getKey();
            boolean isLog = tree.logs().contains(new TreeDetector.Pos(b.getX(), b.getY(), b.getZ()));
            List<ItemStack> drops = (isLog || settings.leafDrops()) && event.isDropItems()
                    ? new ArrayList<>(b.getDrops(tool, player))
                    : new ArrayList<>();
            pieces.add(new TimberAnimation.Piece(b.getLocation(), entry.getValue(), drops, isLog));
        }

        // Remove blocks from the world and clear placement markers
        for (Block b : snapshots.keySet()) {
            b.setType(Material.AIR, false);
            placed.set(b, false);
        }

        // Apply tool durability damage
        player.getInventory().setItemInMainHand(tool.damage(cost, player));

        // Record cooldown timestamp
        player.getPersistentDataContainer().set(lastChopKey, PersistentDataType.LONG, System.currentTimeMillis());

        // Replant sapling
        replantSapling(tree, initial, pieces);

        // Play Display Entity tree tilt animation
        animation.play(pieces, initial.getLocation(), player.getLocation(), player, settings, () -> {});
    }

    private void replantSapling(TreeDetector.Tree tree, Block initial, List<TimberAnimation.Piece> pieces) {
        if (!settings.replant()) return;

        Block below = initial.getRelative(BlockFace.DOWN);
        if (!settings.detection().soil().contains(below.getType().name())) return;

        Material saplingMat = Material.matchMaterial(tree.definition().sapling());
        if (saplingMat == null) return;

        if (settings.replantAlways()) {
            // Always replant without consuming drops
            plantSapling(initial, saplingMat);
        } else {
            // Consume 1 sapling from the tree's actual drops
            for (TimberAnimation.Piece piece : pieces) {
                for (Iterator<ItemStack> it = piece.drops().iterator(); it.hasNext(); ) {
                    ItemStack drop = it.next();
                    if (drop.getType() == saplingMat) {
                        if (drop.getAmount() <= 1) {
                            it.remove();
                        } else {
                            drop.setAmount(drop.getAmount() - 1);
                        }
                        plantSapling(initial, saplingMat);
                        return;
                    }
                }
            }
        }
    }

    private void plantSapling(Block initial, Material saplingMat) {
        initial.setType(saplingMat, true);
        if (settings.sounds()) {
            var sg = saplingMat.createBlockData().getSoundGroup();
            initial.getWorld().playSound(initial.getLocation().add(0.5, 0.5, 0.5), sg.getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 1.0f);
        }
        if (settings.replantProtectSeconds() > 0) {
            Location loc = initial.getLocation();
            protectedSaplings.add(loc);
            getServer().getScheduler().runTaskLater(
                    this,
                    () -> protectedSaplings.remove(loc),
                    settings.replantProtectSeconds() * 20L
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        animation.unload(event.getChunk());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        animation.clean(event.getChunk());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);

        if (action.equals("reload")) {
            if (!sender.hasPermission("lumitimber.reload")) {
                sender.sendMessage(Component.text("You do not have permission to reload LumiTimber.", NamedTextColor.RED));
                return true;
            }
            reloadConfig();
            try {
                settings = Settings.load(getConfig());
                sender.sendMessage(Component.text("LumiTimber configuration successfully reloaded.", NamedTextColor.GREEN));
            } catch (IllegalArgumentException | NullPointerException e) {
                sender.sendMessage(Component.text("Invalid configuration; previous settings retained: " + e.getMessage(), NamedTextColor.RED));
            }
            return true;
        }

        if (action.equals("toggle")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can toggle tree felling.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("lumitimber.chop")) {
                sender.sendMessage(Component.text("You do not have permission to use tree felling.", NamedTextColor.RED));
                return true;
            }

            var pdc = player.getPersistentDataContainer();
            boolean currentlyDisabled = pdc.has(toggleKey);
            if (currentlyDisabled) {
                pdc.remove(toggleKey);
                player.sendMessage(Component.text("Tree felling enabled.", NamedTextColor.GREEN));
            } else {
                pdc.set(toggleKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(Component.text("Tree felling disabled.", NamedTextColor.YELLOW));
            }
            return true;
        }

        sender.sendMessage(Component.text("Usage: /" + label + " [toggle|reload]", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>();
        options.add("toggle");
        if (sender.hasPermission("lumitimber.reload")) {
            options.add("reload");
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}

