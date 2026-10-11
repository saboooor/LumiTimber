package ca.saboor.lumitimber;

import org.bukkit.*;
import org.bukkit.block.Block;
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
        final Vector fallDir;
        final Vector3f rotationAxis;
        final List<DisplayPiece> pieces;
        final Vector3f highestTrunkOffset;
        final BlockData initialLogData;
        final Player player;
        final Settings settings;
        final int chainDepth;
        final Runnable onComplete;
        final int totalDurationTicks;
        final float maxAngleRad;
        int ageTicks = 0;
        boolean finished = false;

        // Bounce/rebound state
        boolean impactTriggered = false;
        int bounceTicks = 0;
        float impactAngleRad = 0.0f;

        ActiveTreeFall(World world, Location pivot, Vector fallDir, Vector3f rotationAxis,
                       List<DisplayPiece> pieces, Vector3f highestTrunkOffset, BlockData initialLogData,
                       Player player, Settings settings, int chainDepth, Runnable onComplete) {
            this.world = world;
            this.pivot = pivot;
            this.fallDir = fallDir;
            this.rotationAxis = rotationAxis;
            this.pieces = pieces;
            this.highestTrunkOffset = highestTrunkOffset;
            this.initialLogData = initialLogData;
            this.player = player;
            this.settings = settings;
            this.chainDepth = chainDepth;
            this.onComplete = onComplete;
            this.totalDurationTicks = Math.max(10, settings.animationDurationTicks());
            this.maxAngleRad = (float) Math.toRadians(88.0);
        }

        void tick() {
            if (finished) return;

            // Handle rebound bounce phase after ground impact
            if (impactTriggered) {
                bounceTicks += 2;
                // Rebound arc: brief slight rebound upwards (~4.5 degrees) then settle back down
                float bounceAngleOffset = (float) Math.toRadians(4.5);
                float currentAngle;
                if (bounceTicks <= 2) {
                    // Ticks 0..2: bounce up
                    float fraction = (float) bounceTicks / 2.0f;
                    currentAngle = impactAngleRad - (bounceAngleOffset * fraction);
                } else if (bounceTicks <= 4) {
                    // Ticks 2..4: settle back down
                    float fraction = (float) (bounceTicks - 2) / 2.0f;
                    currentAngle = (impactAngleRad - bounceAngleOffset) + (bounceAngleOffset * fraction);
                } else {
                    currentAngle = impactAngleRad;
                }
                currentAngle = Math.max(0.0f, currentAngle);

                Quaternionf currentQ = new Quaternionf().setAngleAxis(
                        currentAngle, rotationAxis.x, rotationAxis.y, rotationAxis.z
                );

                if (bounceTicks >= 6) {
                    finishFall(this, currentQ);
                    return;
                }

                applyTransform(currentQ, 2);
                return;
            }

            ageTicks += 2;

            // Wood creaking and strain sounds as the tree bends and tilts under gravity
            if (settings.sounds()) {
                // Initial strain and creak as the hinge snaps and leans (around 15-25% through the fall)
                if (ageTicks >= totalDurationTicks * 0.18 && ageTicks < totalDurationTicks * 0.18 + 2) {
                    world.playSound(pivot, Sound.ENTITY_CREAKING_SWAY, SoundCategory.BLOCKS, 1.3f, 0.85f);
                    world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.9f, 0.75f);
                }
                // Secondary groaning creak as momentum builds (around 45-55% through the fall)
                if (ageTicks >= totalDurationTicks * 0.48 && ageTicks < totalDurationTicks * 0.48 + 2) {
                    world.playSound(pivot, Sound.ENTITY_CREAKING_SWAY, SoundCategory.BLOCKS, 1.5f, 0.7f);
                    world.playSound(pivot, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 0.8f, 0.7f);
                    world.playSound(pivot, Sound.BLOCK_GRASS_STEP, SoundCategory.BLOCKS, 1.2f, 0.7f);
                    world.playSound(pivot, Sound.ITEM_ARMOR_EQUIP_ELYTRA, SoundCategory.BLOCKS, 0.8f, 0.5f);
                }
                // Final tearing rush right before impact (around 75-85% through the fall)
                if (ageTicks >= totalDurationTicks * 0.78 && ageTicks < totalDurationTicks * 0.78 + 2) {
                    world.playSound(pivot, Sound.ENTITY_CREAKING_TWITCH, SoundCategory.BLOCKS, 1.4f, 0.65f);
                    world.playSound(pivot, Sound.BLOCK_CHERRY_WOOD_BREAK, SoundCategory.BLOCKS, 1.1f, 0.55f);
                }
            }

            float progress = Math.min(1.0f, (float) ageTicks / (float) totalDurationTicks);
            // Natural gravitational acceleration: angular acceleration tau ~ sin(theta) -> theta ~ t^2.2
            float eased = (float) Math.pow(progress, 2.2);
            float currentAngle = maxAngleRad * eased;

            Quaternionf currentQ = new Quaternionf().setAngleAxis(
                    currentAngle, rotationAxis.x, rotationAxis.y, rotationAxis.z
            );

            // Check for terrain contact using the top of the trunk once partially fallen
            boolean terrainHit = false;
            if (highestTrunkOffset != null && ageTicks >= (int) (totalDurationTicks * 0.6)) {
                Vector3f topRotated = currentQ.transform(highestTrunkOffset, new Vector3f());
                double worldX = pivot.getX() + topRotated.x + 0.5;
                double worldY = pivot.getY() + topRotated.y + 0.5;
                double worldZ = pivot.getZ() + topRotated.z + 0.5;

                int chunkX = ((int) Math.floor(worldX)) >> 4;
                int chunkZ = ((int) Math.floor(worldZ)) >> 4;
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    int groundY = world.getHighestBlockYAt((int) Math.floor(worldX), (int) Math.floor(worldZ));
                    if (worldY <= groundY + 0.8) {
                        terrainHit = true;
                    }
                }
            }

            if (terrainHit || ageTicks >= totalDurationTicks) {
                triggerImpact(currentQ, currentAngle);
                return;
            }

            applyTransform(currentQ, 2);
        }

        private void triggerImpact(Quaternionf impactQ, float currentAngle) {
            applyImpactEffects(impactQ);
            applyImpactDamage(impactQ);
            applyDominoImpact(impactQ);

            if (settings.animationBounce()) {
                this.impactTriggered = true;
                this.impactAngleRad = currentAngle;
                this.bounceTicks = 0;
            } else {
                finishFall(this, impactQ);
            }
        }

        private void applyDominoImpact(Quaternionf finalQ) {
            if (!settings.domino() || chainDepth >= settings.maxDominoChain()) return;

            Block closestStruck = null;
            double minDistanceSq = Double.MAX_VALUE;

            for (DisplayPiece dp : pieces) {
                Vector3f centerOffset = new Vector3f(dp.initialOffset).add(0.5f, 0.5f, 0.5f);
                Vector3f rotatedCenter = finalQ.transform(centerOffset, new Vector3f());
                int bx = (int) Math.floor(pivot.getX() + rotatedCenter.x);
                int by = (int) Math.floor(pivot.getY() + rotatedCenter.y);
                int bz = (int) Math.floor(pivot.getZ() + rotatedCenter.z);

                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;

                // Check volume around landing piece for standing tree blocks (logs, leaves, vines)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Block b = world.getBlockAt(bx + dx, by + dy, bz + dz);
                            Block logCandidate = findTreeLogFromContact(b);
                            if (logCandidate != null) {
                                double dSq = logCandidate.getLocation().distanceSquared(pivot);
                                if (dSq < minDistanceSq) {
                                    minDistanceSq = dSq;
                                    closestStruck = logCandidate;
                                }
                            }
                        }
                    }
                }
            }

            // Trigger domino only on the single closest hit tree to avoid wide forest wipeouts
            if (closestStruck != null) {
                plugin.tryDominoFell(closestStruck, fallDir, player, chainDepth);
            }
        }

        private Block findTreeLogFromContact(Block block) {
            if (isStandingTreeLog(block)) {
                return block;
            }
            // If contact is made with leaves or vines, search nearby (radius 4) for the tree log
            if (isTreeCanopyOrVine(block)) {
                for (int dy = 2; dy >= -4; dy--) {
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            Block neighbor = block.getRelative(dx, dy, dz);
                            if (isStandingTreeLog(neighbor)) {
                                return neighbor;
                            }
                        }
                    }
                }
            }
            return null;
        }

        private boolean isTreeCanopyOrVine(Block block) {
            Material mat = block.getType();
            if (mat == Material.VINE || mat == Material.WEEPING_VINES || mat == Material.PALE_HANGING_MOSS) {
                return true;
            }
            String name = mat.name();
            for (TreeDetector.Definition def : settings.trees()) {
                if (def.leaves().contains(name)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isStandingTreeLog(Block block) {
            String name = block.getType().name();
            for (TreeDetector.Definition def : settings.trees()) {
                if (def.logs().contains(name)) {
                    return true;
                }
            }
            return false;
        }

        private void applyTransform(Quaternionf q, int interpolationDuration) {
            Vector3f scaleOne = new Vector3f(1.0f, 1.0f, 1.0f);
            Quaternionf identity = new Quaternionf();

            for (DisplayPiece dp : pieces) {
                if (!dp.display.isValid()) continue;
                Vector3f translation = q.transform(dp.initialOffset, new Vector3f());
                Transformation transform = new Transformation(translation, q, scaleOne, identity);
                dp.display.setInterpolationDelay(0);
                dp.display.setInterpolationDuration(interpolationDuration);
                dp.display.setTransformation(transform);
            }
        }

        private void applyImpactEffects(Quaternionf finalQ) {
            if (settings.sounds()) {
                // Loud and deep multi-layered wood crash
                // 1. Deep heavy body thud / bass impact
                world.playSound(pivot, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.BLOCKS, 2.0f, 0.55f);
                world.playSound(pivot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.7f, 0.6f);
                // 2. Loud splintering and cracking wood at low pitch
                world.playSound(pivot, Sound.BLOCK_WOOD_FALL, SoundCategory.BLOCKS, 2.5f, 0.5f);
                world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 2.0f, 0.6f);
                world.playSound(pivot, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.4f, 0.65f);
                // 3. Foliage and ground displacement crash
                world.playSound(pivot, Sound.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1.6f, 0.6f);
                if (settings.animationBounce()) {
                    world.playSound(pivot, Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.BLOCKS, 0.8f, 1.3f);
                }
            }

            if (settings.particles()) {
                world.spawnParticle(Particle.POOF, pivot.clone().add(0, 0.3, 0), 24, 0.8, 0.3, 0.8, 0.08);
            }
        }

        private void applyImpactDamage(Quaternionf finalQ) {
            if (!settings.damagePlayers()) return;

            Set<UUID> damagedEntities = new HashSet<>();
            Vector knockback = fallDir.clone().normalize().multiply(0.8).setY(0.35);

            for (DisplayPiece dp : pieces) {
                Vector3f centerOffset = new Vector3f(dp.initialOffset).add(0.5f, 0.5f, 0.5f);
                Vector3f rotatedCenter = finalQ.transform(centerOffset, new Vector3f());
                Location loc = new Location(
                        world,
                        pivot.getX() + rotatedCenter.x,
                        pivot.getY() + rotatedCenter.y,
                        pivot.getZ() + rotatedCenter.z
                );

                for (Entity entity : world.getNearbyEntities(loc, 1.6, 1.6, 1.6)) {
                    if (entity instanceof Player victim && !damagedEntities.contains(victim.getUniqueId())) {
                        if (victim.getGameMode() == GameMode.SURVIVAL || victim.getGameMode() == GameMode.ADVENTURE) {
                            damagedEntities.add(victim.getUniqueId());
                            victim.damage(settings.damageAmount());
                            victim.setVelocity(victim.getVelocity().add(knockback));
                            victim.sendActionBar(net.kyori.adventure.text.Component.text(
                                    "Crushed by a falling tree!",
                                    net.kyori.adventure.text.format.NamedTextColor.RED
                            ));
                            world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, 0.9f);
                        }
                    }
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
        this.ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
    }

    void play(List<Piece> pieces, Location origin, Vector direction, Player player, Settings settings, int chainDepth, Runnable onComplete) {
        if (!settings.animated() || pieces.isEmpty() || totalActiveBlocks() + pieces.size() > settings.maxEntities()) {
            if (settings.sounds() && !pieces.isEmpty()) {
                var sg = pieces.getFirst().data().getSoundGroup();
                origin.getWorld().playSound(origin, sg.getBreakSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
            }
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

        // Fall direction
        Vector fallDir = direction != null ? direction.clone().setY(0) : new Vector(1, 0, 0);
        if (fallDir.lengthSquared() < 0.0001) {
            fallDir = new Vector(1, 0, 0);
        }
        fallDir.normalize();

        // Only roll fall towards player on initial chop (chainDepth == 0)
        if (chainDepth == 0) {
            boolean fallsTowards = Math.random() < settings.fallTowardsPlayerChance();
            if (fallsTowards) {
                fallDir.multiply(-1.0);
                if (settings.sounds()) {
                    world.playSound(pivot, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.0f, 1.3f);
                }
            }
        }

        // Rotation axis: perpendicular to up-vector and fall direction: axis = (0, 1, 0) x (dx, 0, dz) = (dz, 0, -dx)
        Vector3f rotationAxis = new Vector3f((float) fallDir.getZ(), 0.0f, (float) -fallDir.getX()).normalize();

        // Identity transformation for initial frame (theta = 0)
        Vector3f scaleOne = new Vector3f(1.0f, 1.0f, 1.0f);
        Quaternionf rotIdentity = new Quaternionf();

        List<DisplayPiece> displayPieces = new ArrayList<>(pieces.size());
        Vector3f highestTrunkOffset = null;
        int maxTrunkY = Integer.MIN_VALUE;

        for (Piece piece : pieces) {
            float rx = (float) (piece.source().getBlockX() - pivot.getX());
            float ry = (float) (piece.source().getBlockY() - pivot.getY());
            float rz = (float) (piece.source().getBlockZ() - pivot.getZ());
            Vector3f offset = new Vector3f(rx, ry, rz);

            if (piece.isLog() && piece.source().getBlockY() > maxTrunkY) {
                maxTrunkY = piece.source().getBlockY();
                highestTrunkOffset = offset;
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
            world.playSound(pivot, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.4f, 0.65f);
            world.playSound(pivot, Sound.BLOCK_CHERRY_WOOD_BREAK, SoundCategory.BLOCKS, 1.2f, 0.55f);
            world.playSound(pivot, Sound.ENTITY_CREAKING_SWAY, SoundCategory.BLOCKS, 1.2f, 0.8f);
        }
        if (settings.particles() && !pieces.isEmpty()) {
            world.spawnParticle(Particle.BLOCK, pivot.clone().add(0, 0.5, 0), 24, 0.3, 0.3, 0.3, pieces.getFirst().data());
        }

        ActiveTreeFall fall = new ActiveTreeFall(
                world, pivot, fallDir, rotationAxis, displayPieces, highestTrunkOffset,
                pieces.getFirst().data(), player, settings, chainDepth, onComplete
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

        // If not already played during triggerImpact, play ground impact sounds & particles
        if (!fall.impactTriggered) {
            fall.applyImpactEffects(finalQ);
            fall.applyImpactDamage(finalQ);
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

