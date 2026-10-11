package ca.saboor.lumitimber;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

public record Settings(
        List<TreeDetector.Definition> trees,
        TreeDetector.Options detection,
        Set<String> disabledWorlds,
        boolean requireAxe,
        ToppleMode toppleMode,
        boolean damagePerLog,
        boolean protectTool,
        boolean replant,
        boolean replantAlways,
        int replantProtectSeconds,
        boolean leafDrops,
        boolean realisticDrops,
        boolean addItemsToInventory,
        boolean sounds,
        boolean particles,
        boolean animated,
        int animationDurationTicks,
        int maxEntities,
        int cooldownTicks
) {
    public enum ToppleMode {
        ALWAYS, SNEAKING, NOT_SNEAKING;

        public static ToppleMode fromString(String str) {
            if (str == null) return ALWAYS;
            return switch (str.toUpperCase(Locale.ROOT)) {
                case "SNEAKING", "SNEAK", "TRUE" -> SNEAKING;
                case "NOT_SNEAKING", "NO_SNEAK" -> NOT_SNEAKING;
                default -> ALWAYS;
            };
        }
    }

    public static Settings load(FileConfiguration c) {
        List<TreeDetector.Definition> trees = new ArrayList<>();
        ConfigurationSection section = Objects.requireNonNull(c.getConfigurationSection("trees"), "Missing trees section in configuration");
        for (String key : section.getKeys(false)) {
            ConfigurationSection t = Objects.requireNonNull(section.getConfigurationSection(key));
            Set<String> logs = materials(t.getStringList("logs"));
            Set<String> leaves = materials(t.getStringList("leaves"));
            if (logs.isEmpty() || leaves.isEmpty()) {
                throw new IllegalArgumentException("Empty tree logs or leaves definition for: " + key);
            }
            String sapling = t.getString("sapling", "");
            if (Material.matchMaterial(sapling) == null) {
                throw new IllegalArgumentException("Invalid sapling material '" + sapling + "' for tree: " + key);
            }
            trees.add(new TreeDetector.Definition(
                    key,
                    logs,
                    leaves,
                    sapling,
                    bounded(t.getDouble("branch-radius", 6.0), 0.0, 64.0),
                    (int) bounded(t.getInt("leaf-radius", 6), 1, 16),
                    t.getBoolean("diagonal-leaves", false)
            ));
        }

        int maxLogs = (int) bounded(c.getInt("detection.max-logs", 150), 1, 1000);
        int minLeaves = (int) bounded(c.getInt("detection.minimum-leaves", 5), 1, maxLogs * 8);
        boolean upwardsOnly = c.getBoolean("detection.upwards-only", true);
        boolean destroyLeaves = c.getBoolean("detection.destroy-leaves", true);
        boolean requireCutBase = c.getBoolean("detection.require-cut-base", false);
        Set<String> soil = materials(c.getStringList("replant.soil"));

        TreeDetector.Options detectionOptions = new TreeDetector.Options(
                maxLogs, minLeaves, upwardsOnly, destroyLeaves, requireCutBase, soil
        );

        ToppleMode toppleMode;
        if (c.contains("topple-mode")) {
            toppleMode = ToppleMode.fromString(c.getString("topple-mode"));
        } else {
            toppleMode = c.getBoolean("sneak-only", false) ? ToppleMode.SNEAKING : ToppleMode.ALWAYS;
        }

        return new Settings(
                List.copyOf(trees),
                detectionOptions,
                Set.copyOf(c.getStringList("disabled-worlds")),
                c.getBoolean("require-axe", true),
                toppleMode,
                c.getBoolean("tool.damage-per-log", true),
                c.getBoolean("tool.protect", true),
                c.getBoolean("replant.enabled", true),
                c.getBoolean("replant.always", false),
                (int) bounded(c.getInt("replant.protect-seconds", 3), 0, 60),
                c.getBoolean("drops.leaves", true),
                c.getBoolean("drops.realistic", true),
                c.getBoolean("drops.add-to-inventory", false),
                c.getBoolean("effects.sounds", true),
                c.getBoolean("effects.particles", true),
                c.getBoolean("animation.enabled", true),
                (int) bounded(c.getInt("animation.fall-duration-ticks", 26), 10, 100),
                (int) bounded(c.getInt("animation.max-active-blocks", 2000), 1, 10000),
                (int) bounded(c.getInt("cooldown-ticks", 20), 0, 72000)
        );
    }

    private static double bounded(double v, double min, double max) {
        if (!Double.isFinite(v) || v < min || v > max) {
            throw new IllegalArgumentException("Configuration value outside allowed bounds [" + min + ".." + max + "]: " + v);
        }
        return v;
    }

    private static Set<String> materials(List<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isBlock()) {
                throw new IllegalArgumentException("Invalid block material: " + name);
            }
            result.add(m.name());
        }
        return result;
    }
}

