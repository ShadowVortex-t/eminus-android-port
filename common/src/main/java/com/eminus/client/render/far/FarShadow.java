package com.eminus.client.render.far;

import java.util.List;

import com.eminus.cell.CellFrame;
import com.eminus.client.frame.GameFrame;
import com.eminus.client.model.ModelAtlas;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.pass.Pass;
import com.eminus.handoff.NearSections;
import com.eminus.mesh.MeshSummary;
import com.eminus.render.arena.MeshSlots;
import com.eminus.render.far.CameraOrigin;
import com.eminus.render.far.DrawCommands;
import com.eminus.render.far.ShadowCasterVolume;

import it.unimi.dsi.fastutil.longs.Long2IntFunction;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

public final class FarShadow implements AutoCloseable {
    private static final int FIRST_COMMAND = 0;

    private final Gpu gpu;
    private final FarFrame frame;
    private final DrawCommands commands = new DrawCommands(FarRenderer.START_COMMANDS);
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final ShadowCasterVolume casters = new ShadowCasterVolume();
    private final Vector3f lightTravel = new Vector3f();

    private IndirectCommands indirect;

    private FarShadow(Gpu gpu, FarFrame frame, IndirectCommands indirect) {
        this.gpu = gpu;
        this.frame = frame;
        this.indirect = indirect;
    }

    static FarShadow create(Gpu gpu) {
        return new FarShadow(gpu, FarFrame.create(gpu), IndirectCommands.create(gpu, FarRenderer.START_COMMANDS));
    }

    boolean write(List<MeshSummary> meshes, List<MeshSummary> translucent, Long2IntFunction borderFaces,
            MeshSlots slots, CellFrame cells, GameFrame game, ModelAtlas atlas, NearSections near,
            Matrix4fc cameraViewProjection, Matrix4fc shadowView, Matrix4fc shadowProjection) {
        project(cameraViewProjection, shadowView, shadowProjection);
        commands.writeShadow(meshes, translucent, borderFaces, slots, cells, frustum, casters, game.eyeX(),
                game.eyeY(), game.eyeZ());
        if (commands.count() == 0) {
            return false;
        }

        writeIndirect();
        frame.write(viewProjection, view, cells.minBlockY(), atlas, near, game.shade(), game.smoothLighting(),
                game.rgss(), game.anisotropy(), CameraOrigin.of(game.eyeX(), game.eyeY(), game.eyeZ()));
        return true;
    }

    private void project(Matrix4fc cameraViewProjection, Matrix4fc shadowView, Matrix4fc shadowProjection) {
        view.set(shadowView);
        projection.set(shadowProjection);
        projection.mul(view, viewProjection);
        frustum.set(viewProjection);
        casters.set(cameraViewProjection, view.positiveZ(lightTravel).negate());
    }

    private void writeIndirect() {
        if (indirect.capacity() < commands.capacity()) {
            indirect.close();
            indirect = IndirectCommands.create(gpu, commands.capacity());
        }

        indirect.write(commands);
    }

    public Matrix4fc view() {
        return view;
    }

    public Matrix4fc projection() {
        return projection;
    }

    Buffer frameBuffer() {
        return frame.buffer();
    }

    public int opaqueCount() {
        return commands.opaqueCount();
    }

    public int translucentCount() {
        return commands.translucentCount();
    }

    public void draw(Pass pass) {
        pass.drawIndexedIndirectUnordered(indirect.buffer(), FIRST_COMMAND, commands.opaqueCount());
    }

    public void drawTranslucent(Pass pass) {
        pass.drawIndexedIndirect(indirect.buffer(), commands.opaqueCount(), commands.translucentCount());
    }

    @Override
    public void close() {
        indirect.close();
        frame.close();
    }
}
