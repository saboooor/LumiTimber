package ca.saboor.lumitimber;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Animated tree felling using modern Minecraft Display Entities (BlockDisplay).
 * Tilts the entire tree as a single unified rigid body around the stump cut hinge
 * with client-side interpolation for 60+ FPS motion without server tick lag.
 */
final class TimberAnimation implements AutoCloseable {

    record Piece(Location source, BlockData data, List<ItemStack> drops, boolean isLog) {}

    private static final class DisplayPiece {
        final BlockDisplay display;
        final Vector3f initialOffset;
        final Piece piece;

        DisplayPiece(BlockDisplay display, Vector3f initialOffset, Piece piece) {
            this.display = display;
            this.initialOffset = initialOffset;
            this.piece = piece;
        }
    }

    private final class ActiveTreeFall {
        final UUID id = UUID.randomUUID();
        final World world;
        final Location pivot;
        final Vector3f rotationAxis;
        final List<DisplayPiece> pieces;
        final Vector3f highestTrunkOffset;
        final BlockData initialLogData;
        final Player player;
        final Settings settings;
        final Runnable onComplete;
        final int totalDurationTicks;
        final float maxAngleRad;
        int ageTicks = 0;
        boolean finished = false;

        ActiveTreeFall(World world, Location pivot, Vector3f rotationAxis, List<DisplayPiece> pieces,
                       Vector3f highestTrunkOffset, BlockData initialLogData, Player player,
                       Settings settings, Runnable onComplete) {
            this.world = world;
            this.pivot = pivot;
            this.rotationAxis = rotationAxis;
            this.pieces = pieces;
            this.highestTrunkOffset = highestTrunkOffset;
            this.initialLogData = initialLogData;
            this.player = player;
            this.settings = settings;
            this.onComplete = onComplete;
            this.totalDurationTicks = Math.max(10, settings.animationDurationTicks());
            this.maxAngleRad = (float) Math.toRadians(88.0);
        }

        void tick() {
            if (finished) return;
            ageTicks++;

            // Mid-fall sound effect (leaves rustling as tree whooshes through the air)
            if (ageTicks == totalDurationTicks / 2 && settings.sounds()) {
                world.playSound(pivot, Sound.BLOCK_GRASS_STEP, 0.9f, 0.8f);
                world.playSound(pivot, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.6f, 0.6f);
            }

            // Update transformation every 2 ticks with 2-tick client interpolation duration
            if (ageTicks % 2 == 0 || ageTicks >= totalDurationTicks) {
                float progress = Math.min(1.0f, (float) (ageTicks + 2) / (float) totalDurationTicks);
                // Natural gravitational acceleration: angular acceleration tau ~ sin(theta) -> theta ~ t^2.2
                float eased = (float) Math.pow(progress, 2.2);
                float currentAngle = maxAngleRad * eased;

                Quaternionf currentQ = new Quaternionf().setAngleAxis(
                        currentAngle, rotationAxis.x, rotationAxis.y, rotationAxis.z
                );

                // Check for terrain contact using the highest trunk block
                if (highestTrunkOffset != null) {
                    Vector3f topRotated = currentQ.transform(highestTrunkOffset, new Vector3f());
                    double worldX = pivot.getX() + topRotated.x + 0.5;
                    double worldY = pivot.getY() + topRotated.y + 0.5;
                    double worldZ = pivot.getZ() + topRotated.z + 0.5;

                    int chunkX = ((int) Math.floor(worldX)) >> 4;
                    int chunkZ = ((int) Math.floor(worldZ)) >> 4;
                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        int groundY = world.getHighestBlockYAt((int) Math.floor(worldX), (int) Math.floor(worldZ));
                        if (worldY <= groundY + 0.8 && ageTicks >= 6) {
                            // Tree hit terrain/obstacle early!
                            finishFall(this, currentQ);
                            return;
                        }
                    }
                }

                if (ageTicks >= totalDurationTicks) {
                    finishFall(this, currentQ);
                    return;
                }

                // Apply smooth client-side interpolation to all display entities
                Transformation transform;
                Vector3f scaleOne = new Vector3f(1.0f, 1.0f, 1.0f);
                Quaternionf identity = new Quaternionf();

                for (DisplayPiece dp : pieces) {
                    if (!dp.display.isValid()) continue;
                    Vector3f translation = currentQ.transform(dp.initialOffset, new Vector3f());
                    transform = new Transformation(translation, currentQ, scaleOne, identity);
                    dp.display.setInterpolationDelay(0);
                    dp.display.setInterpolationDuration(2);
                    dp.display.setTransformation(transform);
                }
            }
        }
    }

    private final LumiTimberPlugin plugin;
    private final NamespacedKey tag;
    private final Map<UUID, ActiveTreeFall> active = new LinkedHashMap<>();
    private final BukkitTask ticker;

    TimberAnimation(LumiTimberPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "animation_display");
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void play(List<Piece> pieces, Location origin, Location playerLoc, Player player, Settings settings, Runnable onComplete) {
        if (!settings.animated() || pieces.isEmpty() || totalActiveBlocks() + pieces.size() > settings.maxEntities()) {
            // Immediate drop without animation
            for (Piece piece : pieces) {
                dropLoot(piece.drops(), piece.source(), player, settings);
            }
            if (onComplete != null) onComplete.run();
            return;
        }

        World world = origin.getWorld();
        if (world == null) return;

        // Stump cut hinge pivot point (centered at the bottom face of the cut log)
        Location pivot = new Location(
                world,
                origin.getBlockX() + 0.5,
                origin.getBlockY(),
                origin.getBlockZ() + 0.5
        );

        // Fall direction: horizontal vector pointing AWAY from where the player chopped
        Vector fallDir = origin.toVector().subtract(playerLoc.toVector()).setY(0);
        if (fallDir.lengthSquared() < 0.0001) {
            fallDir = playerLoc.getDirection().setY(0);
            if (fallDir.lengthSquared() < 0.0001) {
                fallDir = new Vector(1, 0, 0);
            }
        }
        fallDir.normalize();

        // Rotation axis: perpendicular to up-vector and fall direction: axis = (0, 1, 0) x (dx, 0, dz) = (dz, 0, -dx)
        Vector3f rotationAxis = new Vector3f((float) fallDir.getZ(), 0.0f, (float) -fallDir.getX()).normalize();

        // Identity transformation for initial frame (theta = 0)
        Vector3f scaleOne = new Vector3f(1.0f, 1.0f, 1.0f);
        Quaternionf rotIdentity = new Quaternionf();

        List<DisplayPiece> displayPieces = new ArrayList<>(pieces.size());
        Vector3f highestTrunkOffset = null;
        double maxTrunkDistSq = -1.0;

        for (Piece piece : pieces) {
            float rx = (float) (piece.source().getBlockX() - pivot.getX());
            float ry = (float) (piece.source().getBlockY() - pivot.getY());
            float rz = (float) (piece.source().getBlockZ() - pivot.getZ());
            Vector3f offset = new Vector3f(rx, ry, rz);

            if (piece.isLog()) {
                double distSq = (double) rx * rx + (double) ry * ry + (double) rz * rz;
                if (distSq > maxTrunkDistSq) {
                    maxTrunkDistSq = distSq;
                    highestTrunkOffset = offset;
                }
            }

            try {
                BlockDisplay display = world.spawn(pivot, BlockDisplay.class, d -> {
                    d.setBlock(piece.data());
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setPersistent(false);
                    d.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                    d.setDisplayWidth(32.0f);
                    d.setDisplayHeight(32.0f);
                    d.setViewRange(1.5f);
                    d.setShadowRadius(0.0f);
                    d.setTransformation(new Transformation(offset, rotIdentity, scaleOne, rotIdentity));
                });
                displayPieces.add(new DisplayPiece(display, offset, piece));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Failed to spawn BlockDisplay for timber piece: " + e.getMessage());
                dropLoot(piece.drops(), piece.source(), player, settings);
            }
        }

        if (displayPieces.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // Initial wood crack / break sound and strain particles at the cut hinge
        if (settings.sounds()) {
            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.75f);
            world.playSound(pivot, Sound.BLOCK_CHERRY_WOOD_BREAK, 0.8f, 0.6f);
        }
        if (settings.particles() && !pieces.isEmpty()) {
            world.spawnParticle(Particle.BLOCK, pivot.clone().add(0, 0.5, 0), 24, 0.3, 0.3, 0.3, pieces.getFirst().data());
        }

        ActiveTreeFall fall = new ActiveTreeFall(
                world, pivot, rotationAxis, displayPieces, highestTrunkOffset,
                pieces.getFirst().data(), player, settings, onComplete
        );
        active.put(fall.id, fall);
    }

    private void tick() {
        for (ActiveTreeFall fall : List.copyOf(active.values())) {
            fall.tick();
        }
    }

    private void finishFall(ActiveTreeFall fall, Quaternionf finalQ) {
        if (fall.finished) return;
        fall.finished = true;
        active.remove(fall.id);

        World world = fall.world;
        Location pivot = fall.pivot;

        // Ground impact sound effects
        if (fall.settings.sounds()) {
            world.playSound(pivot, Sound.BLOCK_WOOD_FALL, 1.3f, 0.7f);
            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.7f);
            world.playSound(pivot, Sound.BLOCK_GRASS_BREAK, 0.9f, 0.9f);
        }

        // Ground impact dust and wood particles
        if (fall.settings.particles()) {
            world.spawnParticle(Particle.POOF, pivot.clone().add(0, 0.3, 0), 12, 0.5, 0.2, 0.5, 0.05);
        }

        // Drop loot and spawn impact particles at each block's exact landing coordinate
        for (DisplayPiece dp : fall.pieces) {
            Location dropLoc;
            if (fall.settings.realisticDrops()) {
                Vector3f centerOffset = new Vector3f(dp.initialOffset).add(0.5f, 0.5f, 0.5f);
                Vector3f rotatedCenter = finalQ.transform(centerOffset, new Vector3f());
                double lx = pivot.getX() + rotatedCenter.x;
                double ly = pivot.getY() + rotatedCenter.y;
                double lz = pivot.getZ() + rotatedCenter.z;

                dropLoc = new Location(world, lx, ly, lz);
                int chunkX = dropLoc.getBlockX() >> 4;
                int chunkZ = dropLoc.getBlockZ() >> 4;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    int groundY = world.getHighestBlockYAt(dropLoc.getBlockX(), dropLoc.getBlockZ());
                    if (dropLoc.getY() < groundY + 0.2) {
                        dropLoc.setY(groundY + 0.2);
                    }
                } else {
                    dropLoc = pivot;
                }
            } else {
                dropLoc = pivot;
            }

            if (fall.settings.particles() && dp.piece.isLog()) {
                world.spawnParticle(Particle.BLOCK, dropLoc, 8, 0.2, 0.2, 0.2, dp.piece.data());
            }

            dropLoot(dp.piece.drops(), dropLoc, fall.player, fall.settings);
            dp.display.remove();
        }

        if (fall.onComplete != null) {
            fall.onComplete.run();
        }
    }

    private void dropLoot(List<ItemStack> drops, Location loc, Player player, Settings settings) {
        if (drops == null || drops.isEmpty()) return;

        if (settings.addItemsToInventory() && player != null && player.isOnline()) {
            for (ItemStack item : drops) {
                var leftover = player.getInventory().addItem(item);
                for (ItemStack overflow : leftover.values()) {
                    if (loc.getWorld() != null) {
                        loc.getWorld().dropItemNaturally(player.getLocation(), overflow);
                    }
                }
            }
        } else {
            if (loc.getWorld() != null && loc.getY() >= loc.getWorld().getMinHeight()) {
                int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
                if (loc.getWorld().isChunkLoaded(cx, cz)) {
                    for (ItemStack item : drops) {
                        loc.getWorld().dropItemNaturally(loc, item);
                    }
                }
            }
        }
    }

    private int totalActiveBlocks() {
        int sum = 0;
        for (ActiveTreeFall fall : active.values()) {
            sum += fall.pieces.size();
        }
        return sum;
    }

    boolean owns(Entity entity) {
        return entity.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    void unload(Chunk chunk) {
        for (ActiveTreeFall fall : List.copyOf(active.values())) {
            int cx = fall.pivot.getBlockX() >> 4;
            int cz = fall.pivot.getBlockZ() >> 4;
            if (fall.world.equals(chunk.getWorld()) && cx == chunk.getX() && cz == chunk.getZ()) {
                Quaternionf q = new Quaternionf().setAngleAxis(fall.maxAngleRad, fall.rotationAxis.x, fall.rotationAxis.y, fall.rotationAxis.z);
                finishFall(fall, q);
            }
        }
    }

    void clean(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (owns(entity)) {
                boolean isActive = false;
                for (ActiveTreeFall fall : active.values()) {
                    for (DisplayPiece dp : fall.pieces) {
                        if (dp.display.getUniqueId().equals(entity.getUniqueId())) {
                            isActive = true;
                            break;
                        }
                    }
                    if (isActive) break;
                }
                if (!isActive) {
                    entity.remove();
                }
            }
        }
    }

    @Override
    public void close() {
        ticker.cancel();
        for (ActiveTreeFall fall : List.copyOf(active.values())) {
            Quaternionf q = new Quaternionf().setAngleAxis(fall.maxAngleRad, fall.rotationAxis.x, fall.rotationAxis.y, fall.rotationAxis.z);
            finishFall(fall, q);
        }
        active.clear();
    }
}

