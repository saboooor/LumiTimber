package ca.saboor.lumitimber;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.TreeSet;

/**
 * Persists player-placed tree blocks directly in Paper Chunk PDC (PersistentDataContainer).
 * This eliminates the need for an external database or memory-leaking global cache.
 * Chunk PDC unloads when chunks unload and saves natively in Minecraft anvil chunk data.
 */
final class PlacedBlocks {
    private final NamespacedKey key;

    PlacedBlocks(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed_blocks");
    }

    private int position(Block b) {
        int yOffset = b.getY() - b.getWorld().getMinHeight();
        return (yOffset << 8) | ((b.getX() & 15) << 4) | (b.getZ() & 15);
    }

    boolean contains(Block b) {
        int[] values = b.getChunk().getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return values != null && Arrays.binarySearch(values, position(b)) >= 0;
    }

    void set(Block b, boolean placed) {
        var pdc = b.getChunk().getPersistentDataContainer();
        int[] values = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        TreeSet<Integer> positions = new TreeSet<>();
        if (values != null) {
            for (int value : values) {
                positions.add(value);
            }
        }
        int pos = position(b);
        if (placed) {
            positions.add(pos);
        } else {
            positions.remove(pos);
        }

        if (positions.isEmpty()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, positions.stream().mapToInt(Integer::intValue).toArray());
        }
    }
}

