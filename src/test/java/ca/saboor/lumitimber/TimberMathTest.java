package ca.saboor.lumitimber;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimberMathTest {

    @Test
    void testRotationAxisCalculation() {
        // Player at (0, 0), cut block at (0, 5) -> falls North (dz = 5)
        // Fall direction: (0, 0, 1)
        Vector3f fallDir = new Vector3f(0f, 0f, 1f).normalize();

        // axis = up x fallDir = (0, 1, 0) x (0, 0, 1) = (1, 0, 0)
        Vector3f axis = new Vector3f(fallDir.z, 0f, -fallDir.x).normalize();

        assertEquals(1.0f, axis.x, 1e-6);
        assertEquals(0.0f, axis.y, 1e-6);
        assertEquals(0.0f, axis.z, 1e-6);

        // Rotating (0, 1, 0) by 90 degrees around (1, 0, 0):
        // (0, 1, 0) rotates towards (0, 0, 1), which is the fall direction!
        Quaternionf q = new Quaternionf().setAngleAxis((float) Math.toRadians(90.0), axis.x, axis.y, axis.z);
        Vector3f tiltedUp = q.transform(new Vector3f(0f, 1f, 0f), new Vector3f());

        assertEquals(0.0f, tiltedUp.x, 1e-5);
        assertEquals(0.0f, tiltedUp.y, 1e-5);
        assertEquals(1.0f, tiltedUp.z, 1e-5);
    }

    @Test
    void testRigidBodyInvariance() {
        // Verify that the distance between any two blocks in the tree is perfectly preserved under rotation
        Vector3f blockA = new Vector3f(0f, 0f, 0f); // Stump cut block
        Vector3f blockB = new Vector3f(0f, 10f, 0f); // Top of trunk
        Vector3f blockC = new Vector3f(2f, 8f, 1f);  // Branch block

        float distAB_initial = blockA.distance(blockB);
        float distBC_initial = blockB.distance(blockC);
        float distAC_initial = blockA.distance(blockC);

        // Arbitrary fall direction: (-0.6, 0, 0.8)
        Vector3f fallDir = new Vector3f(-0.6f, 0.0f, 0.8f).normalize();
        Vector3f axis = new Vector3f(fallDir.z, 0.0f, -fallDir.x).normalize();

        // Rotate through various angles from 0 to 90 degrees
        for (double deg = 0; deg <= 90; deg += 15) {
            float angleRad = (float) Math.toRadians(deg);
            Quaternionf q = new Quaternionf().setAngleAxis(angleRad, axis.x, axis.y, axis.z);

            Vector3f rotA = q.transform(blockA, new Vector3f());
            Vector3f rotB = q.transform(blockB, new Vector3f());
            Vector3f rotC = q.transform(blockC, new Vector3f());

            assertEquals(distAB_initial, rotA.distance(rotB), 1e-5, "Distance AB must be invariant");
            assertEquals(distBC_initial, rotB.distance(rotC), 1e-5, "Distance BC must be invariant");
            assertEquals(distAC_initial, rotA.distance(rotC), 1e-5, "Distance AC must be invariant");
        }
    }
}

