package ca.saboor.lumitimber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TreeDetectorTest {

    private TreeDetector detector;
    private List<TreeDetector.Definition> definitions;
    private TreeDetector.Options defaultOptions;

    @BeforeEach
    void setup() {
        detector = new TreeDetector();
        definitions = List.of(
                new TreeDetector.Definition(
                        "oak",
                        Set.of("OAK_LOG"),
                        Set.of("OAK_LEAVES"),
                        "OAK_SAPLING",
                        6.0,
                        6,
                        false
                ),
                new TreeDetector.Definition(
                        "birch",
                        Set.of("BIRCH_LOG"),
                        Set.of("BIRCH_LEAVES"),
                        "BIRCH_SAPLING",
                        1.0,
                        4,
                        false
                )
        );
        defaultOptions = new TreeDetector.Options(
                150,
                5,
                true,
                true,
                false,
                Set.of("DIRT", "GRASS_BLOCK")
        );
    }

    private static class MockWorld implements TreeDetector.Access {
        final Map<TreeDetector.Pos, String> blocks = new HashMap<>();
        final Set<TreeDetector.Pos> placedBlocks = new HashSet<>();

        void set(int x, int y, int z, String material) {
            blocks.put(new TreeDetector.Pos(x, y, z), material);
        }

        void setPlaced(int x, int y, int z, boolean placed) {
            TreeDetector.Pos p = new TreeDetector.Pos(x, y, z);
            if (placed) placedBlocks.add(p);
            else placedBlocks.remove(p);
        }

        @Override
        public String material(TreeDetector.Pos pos) {
            return blocks.getOrDefault(pos, "AIR");
        }

        @Override
        public boolean placed(TreeDetector.Pos pos) {
            return placedBlocks.contains(pos);
        }

        @Override
        public boolean available(TreeDetector.Pos pos) {
            return true;
        }
    }

    @Test
    void detectsSimpleOakTree() {
        MockWorld world = new MockWorld();
        // Trunk from y=64 to y=68
        for (int y = 64; y <= 68; y++) {
            world.set(0, y, 0, "OAK_LOG");
        }
        // Canopy of 8 leaves at y=68 around the top
        world.set(1, 68, 0, "OAK_LEAVES");
        world.set(-1, 68, 0, "OAK_LEAVES");
        world.set(0, 68, 1, "OAK_LEAVES");
        world.set(0, 68, -1, "OAK_LEAVES");
        world.set(1, 69, 0, "OAK_LEAVES");
        world.set(-1, 69, 0, "OAK_LEAVES");
        world.set(0, 69, 1, "OAK_LEAVES");
        world.set(0, 69, -1, "OAK_LEAVES");

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), definitions, defaultOptions
        );

        assertNotNull(result);
        assertEquals("oak", result.definition().name());
        assertEquals(5, result.logs().size());
        assertEquals(8, result.leaves().size());
    }

    @Test
    void rejectsTreeWithInsufficientLeaves() {
        MockWorld world = new MockWorld();
        for (int y = 64; y <= 68; y++) {
            world.set(0, y, 0, "OAK_LOG");
        }
        // Only 2 leaves, minimum is 5
        world.set(1, 68, 0, "OAK_LEAVES");
        world.set(-1, 68, 0, "OAK_LEAVES");

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), definitions, defaultOptions
        );

        assertNull(result, "Tree with fewer leaves than minimum-leaves must be rejected");
    }

    @Test
    void ignoresPlayerPlacedLogs() {
        MockWorld world = new MockWorld();
        for (int y = 64; y <= 68; y++) {
            world.set(0, y, 0, "OAK_LOG");
        }
        for (int i = 1; i <= 6; i++) {
            world.set(i, 68, 0, "OAK_LEAVES");
        }
        // Mark chopped log as player-placed
        world.setPlaced(0, 64, 0, true);

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), definitions, defaultOptions
        );

        assertNull(result, "Player-placed initial block must not trigger timber");
    }

    @Test
    void stopsBeforeExceedingMaxLogs() {
        MockWorld world = new MockWorld();
        // 10 logs
        for (int y = 64; y <= 73; y++) {
            world.set(0, y, 0, "OAK_LOG");
        }
        for (int i = 1; i <= 6; i++) {
            world.set(0, 74, i, "OAK_LEAVES");
        }

        // Limit max-logs to 5
        TreeDetector.Options restrictedOptions = new TreeDetector.Options(
                5, 5, true, true, false, Set.of("DIRT")
        );

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), definitions, restrictedOptions
        );

        assertNull(result, "Log structure exceeding max-logs must be rejected");
    }

    @Test
    void doesNotFellNeighboringTree() {
        MockWorld world = new MockWorld();
        // Tree A at x=0
        for (int y = 64; y <= 68; y++) {
            world.set(0, y, 0, "OAK_LOG");
        }
        for (int i = 1; i <= 5; i++) {
            world.set(0, 68, i, "OAK_LEAVES");
        }

        // Neighbor Tree B at x=4
        for (int y = 64; y <= 68; y++) {
            world.set(4, y, 0, "OAK_LOG");
        }
        // Leaf between trees touching Tree B's log at x=3
        world.set(3, 68, 0, "OAK_LEAVES");

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), definitions, defaultOptions
        );

        assertNotNull(result);
        // Ensure Tree B's logs were NOT included in Tree A's felling
        assertFalse(result.logs().contains(new TreeDetector.Pos(4, 64, 0)));
        assertFalse(result.logs().contains(new TreeDetector.Pos(4, 68, 0)));
    }

    @Test
    void detectsPoplarTree() {
        List<TreeDetector.Definition> treeDefs = List.of(
                new TreeDetector.Definition(
                        "poplar",
                        Set.of("BIRCH_LOG", "STRIPPED_BIRCH_LOG"),
                        Set.of("BIRCH_LEAVES", "OAK_LEAVES"),
                        "BIRCH_SAPLING",
                        1.0,
                        4,
                        false
                )
        );

        MockWorld world = new MockWorld();
        // Tall slender Poplar trunk
        for (int y = 64; y <= 72; y++) {
            world.set(0, y, 0, "BIRCH_LOG");
        }
        // Columnar canopy
        for (int y = 68; y <= 73; y++) {
            world.set(1, y, 0, "BIRCH_LEAVES");
            world.set(-1, y, 0, "BIRCH_LEAVES");
            world.set(0, y, 1, "BIRCH_LEAVES");
            world.set(0, y, -1, "BIRCH_LEAVES");
        }

        TreeDetector.Tree result = detector.detect(
                world, new TreeDetector.Pos(0, 64, 0), treeDefs, defaultOptions
        );

        assertNotNull(result);
        assertEquals("poplar", result.definition().name());
        assertEquals(9, result.logs().size());
        assertTrue(result.leaves().size() >= 5);
    }
}

