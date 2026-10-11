package ca.saboor.lumitimber;

import java.util.*;

/**
 * Server-independent bounded tree search algorithm matching UltimateTimber tree detection rules.
 * All world access is performed through the Access interface on the main server thread.
 */
public final class TreeDetector {

    public record Pos(int x, int y, int z) {
        public Pos add(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }

        public double distanceSquared(Pos other) {
            double dx = (double) x - other.x;
            double dy = (double) y - other.y;
            double dz = (double) z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }

        public boolean isWithin(Pos other, double radiusSq, double radius) {
            int dx = Math.abs(x - other.x);
            if (dx > radius) return false;
            int dy = Math.abs(y - other.y);
            if (dy > radius) return false;
            int dz = Math.abs(z - other.z);
            if (dz > radius) return false;
            return (double) dx * dx + (double) dy * dy + (double) dz * dz <= radiusSq;
        }
    }

    public interface Access {
        String material(Pos pos);
        boolean placed(Pos pos);
        boolean available(Pos pos);
    }

    public record Definition(
            String name,
            Set<String> logs,
            Set<String> leaves,
            String sapling,
            double branchRadius,
            int leafRadius,
            boolean diagonalLeaves
    ) {
        public Definition {
            logs = Set.copyOf(logs);
            leaves = Set.copyOf(leaves);
        }
    }

    public record Options(
            int maxLogs,
            int minLeaves,
            boolean upwards,
            boolean destroyLeaves,
            boolean requireCutBase,
            Set<String> soil
    ) {
        public Options {
            soil = Set.copyOf(soil);
        }
    }

    public record Tree(Definition definition, Set<Pos> logs, Set<Pos> leaves) {
        public Tree {
            logs = Set.copyOf(logs);
            leaves = Set.copyOf(leaves);
        }
    }

    private static final int[][] ALL = offsets(false);
    private static final int[][] UP = offsets(true);
    private static final int[][] FACES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static int[][] offsets(boolean upwards) {
        List<int[]> result = new ArrayList<>();
        int minY = upwards ? 0 : -1;
        for (int y = minY; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        result.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return result.toArray(int[][]::new);
    }

    private static boolean matches(Access world, Pos p, List<Definition> defs, boolean isLog) {
        if (!world.available(p) || world.placed(p)) return false;
        String material = world.material(p);
        for (Definition d : defs) {
            if ((isLog ? d.logs : d.leaves).contains(material)) {
                return true;
            }
        }
        return false;
    }

    private static void narrow(Access world, Pos p, List<Definition> defs, boolean isLog) {
        String material = world.material(p);
        defs.removeIf(d -> !(isLog ? d.logs : d.leaves).contains(material));
    }

    public Tree detect(Access world, Pos start, List<Definition> definitions, Options options) {
        List<Definition> defs = new ArrayList<>(definitions);
        if (!matches(world, start, defs, true)) return null;
        narrow(world, start, defs, true);

        LinkedHashSet<Pos> logs = new LinkedHashSet<>();
        logs.add(start);
        List<Pos> trunk = new ArrayList<>();
        trunk.add(start);

        // 1. Collect straight trunk upwards (and downwards if upwards-only is false)
        int[] directions = options.upwards ? new int[]{1} : new int[]{1, -1};
        for (int direction : directions) {
            Pos p = start;
            while (true) {
                p = p.add(0, direction, 0);
                if (!matches(world, p, defs, true)) break;
                if (logs.size() >= options.maxLogs) return null;
                logs.add(p);
                trunk.add(p);
                narrow(world, p, defs, true);
            }
        }

        // 2. Search branches branching out from the trunk
        ArrayDeque<Pos> queue = new ArrayDeque<>(trunk);
        Set<Pos> visited = new HashSet<>(trunk);
        while (!queue.isEmpty()) {
            Pos p = queue.removeFirst();
            int[][] deltas = options.upwards ? UP : ALL;
            for (int[] delta : deltas) {
                Pos next = p.add(delta[0], delta[1], delta[2]);
                if (!visited.add(next) || !matches(world, next, defs, true)) continue;

                double radius = defs.stream().mapToDouble(Definition::branchRadius).max().orElse(0.0);
                double radiusSq = radius * radius * (options.upwards ? 1.0 : 1.5);
                double maxDist = Math.sqrt(radiusSq);

                boolean withinTrunk = false;
                for (Pos t : trunk) {
                    if (next.isWithin(t, radiusSq, maxDist)) {
                        withinTrunk = true;
                        break;
                    }
                }
                if (!withinTrunk) continue;

                if (logs.size() >= options.maxLogs) return null; // Abort if exceeding limit (e.g. building of logs)
                logs.add(next);
                narrow(world, next, defs, true);
                if (!options.upwards || next.y >= start.y) {
                    queue.addLast(next);
                }
            }
        }

        // 3. Leaf search from all detected logs
        Set<Pos> leaves = new LinkedHashSet<>();
        queue.addAll(logs);
        visited.clear();
        visited.addAll(logs);

        int maxLeaves = Math.multiplyExact(options.maxLogs, 8);
        boolean diagonalLeaves = defs.stream().anyMatch(Definition::diagonalLeaves);
        int[][] leafOffsets = diagonalLeaves ? ALL : FACES;

        List<Pos> logList = new ArrayList<>(logs);
        int leafRadius = defs.stream().mapToInt(Definition::leafRadius).max().orElse(6);
        double leafRadiusSq = (double) leafRadius * leafRadius;

        while (!queue.isEmpty() && leaves.size() < maxLeaves) {
            Pos p = queue.removeFirst();
            for (int[] delta : leafOffsets) {
                Pos next = p.add(delta[0], delta[1], delta[2]);
                if (!visited.add(next) || !matches(world, next, defs, false)) continue;

                boolean withinLog = false;
                for (Pos logPos : logList) {
                    if (next.isWithin(logPos, leafRadiusSq, leafRadius)) {
                        withinLog = true;
                        break;
                    }
                }
                if (!withinLog) continue;

                // Border check: if leaf touches an un-detected log, it belongs to another tree/structure
                boolean bordersOtherTree = false;
                for (int[] around : ALL) {
                    Pos neighbor = next.add(around[0], around[1], around[2]);
                    if (!logs.contains(neighbor) && matches(world, neighbor, defs, true)) {
                        bordersOtherTree = true;
                        break;
                    }
                }
                if (bordersOtherTree) continue;

                leaves.add(next);
                narrow(world, next, defs, false);
                queue.addLast(next);
                if (leaves.size() >= maxLeaves) break;
            }
        }

        // Validate tree integrity
        if (defs.isEmpty() || leaves.size() < options.minLeaves) return null;

        // Cut-base requirement (e.g. for 2x2 trees, other base trunk blocks must not be supported by soil)
        if (options.requireCutBase) {
            for (Pos p : logs) {
                if (!p.equals(start) && p.y == start.y) {
                    Pos below = p.add(0, -1, 0);
                    if (matches(world, below, defs, true) || (world.available(below) && options.soil.contains(world.material(below)))) {
                        return null;
                    }
                }
            }
        }

        return new Tree(defs.getFirst(), logs, options.destroyLeaves ? leaves : Set.of());
    }
}

