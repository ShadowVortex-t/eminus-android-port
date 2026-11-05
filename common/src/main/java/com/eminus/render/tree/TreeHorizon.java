package com.eminus.render.tree;

import com.eminus.cell.DetailLevel;
import com.eminus.settings.FarDistance;

final class TreeHorizon {
    static final double RING_RATIO = 1.5;
    static final double LIFT_RATIO = 1.1;
    static final int LIFT_FRAMES = 300;
    static final double UNBOUNDED = Double.POSITIVE_INFINITY;

    private static final double UNLOCK_BLOCKS_SQUARED = (double) TreeRoots.MOVE_BLOCKS * TreeRoots.MOVE_BLOCKS;
    private static final double[] RING_DIVISORS = ringDivisors();

    private double horizon = UNBOUNDED;
    private boolean lifted;
    private boolean locked;
    private double lockX;
    private double lockZ;
    private int quietFrames;

    static double key(double horizontalDistance, int parentLevel) {
        return horizontalDistance / RING_DIVISORS[parentLevel - 1 - DetailLevel.MIN];
    }

    boolean refines(double key) {
        return key < horizon;
    }

    void lower(double key) {
        horizon = Math.min(horizon, key);
        quietFrames = 0;
    }

    void reset() {
        horizon = UNBOUNDED;
        lifted = false;
        locked = false;
        quietFrames = 0;
    }

    boolean frame(CameraFrame camera, boolean idle) {
        if (locked && moved(camera)) {
            locked = false;
        }

        if (camera.pressure()) {
            quietFrames = 0;
            if (lifted) {
                lifted = false;
                locked = true;
                lockX = camera.eyeX();
                lockZ = camera.eyeZ();
            }

            return false;
        }

        if (settled() || !idle) {
            quietFrames = 0;
            return false;
        }

        if (++quietFrames < LIFT_FRAMES) {
            return false;
        }

        quietFrames = 0;
        lifted = true;
        horizon *= LIFT_RATIO;
        if (horizon >= (double) camera.farChunks() * FarDistance.BLOCKS_PER_CHUNK) {
            horizon = UNBOUNDED;
        }

        return true;
    }

    boolean settled() {
        return !bounded() || locked;
    }

    boolean bounded() {
        return horizon != UNBOUNDED;
    }

    double horizon() {
        return horizon;
    }

    boolean locked() {
        return locked;
    }

    private boolean moved(CameraFrame camera) {
        double dx = camera.eyeX() - lockX;
        double dz = camera.eyeZ() - lockZ;
        return dx * dx + dz * dz >= UNLOCK_BLOCKS_SQUARED;
    }

    private static double[] ringDivisors() {
        double[] divisors = new double[DetailLevel.COUNT];
        for (int level = 0; level < divisors.length; level++) {
            divisors[level] = Math.pow(RING_RATIO, level);
        }

        return divisors;
    }
}
