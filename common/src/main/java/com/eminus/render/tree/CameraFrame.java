package com.eminus.render.tree;

import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

public record CameraFrame(
        double eyeX,
        double eyeY,
        double eyeZ,
        Matrix4fc viewProjection,
        float pixelsPerBlock,
        float inViewPixelsPerBlock,
        int farChunks,
        int subdivisionPixels,
        boolean pressure,
        long frame,
        Quaternionfc rotation,
        float turnMargin) {
    public CameraFrame {
        inViewPixelsPerBlock = Math.max(inViewPixelsPerBlock, pixelsPerBlock);
    }

    boolean zoomed() {
        return inViewPixelsPerBlock > pixelsPerBlock;
    }

    CameraFrame underPressure() {
        return new CameraFrame(eyeX, eyeY, eyeZ, viewProjection, pixelsPerBlock, inViewPixelsPerBlock, farChunks,
                subdivisionPixels, true, frame, rotation, turnMargin);
    }
}
