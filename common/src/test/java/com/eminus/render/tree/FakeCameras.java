package com.eminus.render.tree;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

final class FakeCameras {
    static final int THRESHOLD_PIXELS = 64;
    static final float CLOSE_PIXELS_PER_BLOCK = 1_000.0F;
    static final float FAR_PIXELS_PER_BLOCK = 0.001F;

    private static final float EVERYWHERE = 1.0e7F;
    private static final float FOV_RADIANS = (float) Math.toRadians(70.0);
    private static final float ASPECT = 1.0F;
    private static final float NEAR = 16.0F;
    private static final float FAR = 48_000.0F;

    private static final long FIRST_FRAME = 1L;
    private static final Quaternionfc FORWARD = new Quaternionf();
    private static final float NO_TURN = 0.0F;

    private static final Matrix4fc EVERYTHING = new Matrix4f()
            .ortho(-EVERYWHERE, EVERYWHERE, -EVERYWHERE, EVERYWHERE, -EVERYWHERE, EVERYWHERE);

    static CameraFrame everything(double x, double y, double z, int farChunks, float pixelsPerBlock) {
        return new CameraFrame(x, y, z, EVERYTHING, pixelsPerBlock, pixelsPerBlock, farChunks, THRESHOLD_PIXELS,
                false, FIRST_FRAME, FORWARD, NO_TURN);
    }

    static CameraFrame looking(double x, double y, double z, float dirX, float dirY, float dirZ, int farChunks,
            float pixelsPerBlock) {
        Matrix4f viewProjection = new Matrix4f()
                .perspective(FOV_RADIANS, ASPECT, NEAR, FAR)
                .lookAlong(dirX, dirY, dirZ, 0.0F, 1.0F, 0.0F);
        return new CameraFrame(x, y, z, viewProjection, pixelsPerBlock, pixelsPerBlock, farChunks, THRESHOLD_PIXELS,
                false, FIRST_FRAME, FORWARD, NO_TURN);
    }

    static CameraFrame zoomed(CameraFrame frame, float inViewPixelsPerBlock) {
        return new CameraFrame(frame.eyeX(), frame.eyeY(), frame.eyeZ(), frame.viewProjection(),
                frame.pixelsPerBlock(), inViewPixelsPerBlock, frame.farChunks(), frame.subdivisionPixels(),
                frame.pressure(), frame.frame(), frame.rotation(), frame.turnMargin());
    }

    static CameraFrame underPressure(CameraFrame frame) {
        return frame.underPressure();
    }

    private FakeCameras() {
    }
}
