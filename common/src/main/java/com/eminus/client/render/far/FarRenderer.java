package com.eminus.client.render.far;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.eminus.Eminus;
import com.eminus.cell.CellKey;
import com.eminus.cell.DetailLevel;
import com.eminus.handoff.NearBox;
import com.eminus.handoff.NearPlane;
import com.eminus.handoff.NearReach;
import com.eminus.ingest.IngestService;
import com.eminus.handoff.NearSections;
import com.eminus.client.handoff.NearMaskPass;
import com.eminus.client.handoff.NearSectionTable;
import com.eminus.mesh.CellMesh;
import com.eminus.mesh.MeshService;
import com.eminus.model.PackBlocks;
import com.eminus.client.mesh.MeshWiring;
import com.eminus.client.model.ClientBakery;
import com.eminus.client.model.ModelPublisher;
import com.eminus.client.frame.GameFog;
import com.eminus.client.frame.GameFrame;
import com.eminus.client.frame.GameFrames;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.texture.Sampler;
import com.eminus.gpu.texture.Texture;
import com.eminus.render.arena.ArenaSizing;
import com.eminus.render.arena.MeshSlot;
import com.eminus.client.render.arena.ArenaState;
import com.eminus.client.render.arena.GeometryArena;
import com.eminus.render.backend.BackendSupport;
import com.eminus.render.backend.DepthConvention;
import com.eminus.client.render.backend.BackendCheck;
import com.eminus.render.far.CameraOrigin;
import com.eminus.render.far.CompositeFog;
import com.eminus.render.far.DrawCommands;
import com.eminus.render.far.MeshOrder;
import com.eminus.render.far.TurnMargin;
import com.eminus.render.tree.CameraFrame;
import com.eminus.render.tree.NodeRow;
import com.eminus.render.tree.RenderList;
import com.eminus.render.tree.TreeBatch;
import com.eminus.render.tree.TreeExtent;
import com.eminus.render.tree.TreeManager;
import com.eminus.session.DimensionRuntime;
import com.eminus.session.EminusInstance;
import com.eminus.client.session.ClientSession;
import com.eminus.settings.FarDistance;
import com.eminus.settings.GrassAndFlowers;
import com.eminus.settings.LodQuality;
import com.eminus.settings.Settings;
import com.eminus.settings.SettingsService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

public final class FarRenderer implements AutoCloseable {
    public static final int START_COMMANDS = 32768;
    public static final String ARENA_CAP_PROPERTY = "eminus.arena.maxMiB";
    public static final String ARENA_FLOOR_PROPERTY = "eminus.arena.minMiB";
    public static final String ATLAS_FIRST_MIP_PROPERTY = "eminus.atlas.firstMip";

    private final Gpu gpu;
    private final int levelHeight;
    private final DimensionRuntime runtime;
    private final ClientBakery baking;
    private final ModelPublisher models;
    private final GeometryArena arena;
    private final FarTarget target;
    private final FarFrame frame;
    private final NearMaskPass mask;
    private final NearSectionTable nearSections;
    private final OpaquePass opaque;
    private final TranslucentPass translucent;
    private final CompositePass composite;
    private final FarOverlays overlays;
    private final DrawCommands commands = new DrawCommands(START_COMMANDS);
    private final MeshOrder order = new MeshOrder();
    private final FarProjection projection = new FarProjection();
    private final LevelProjection levelProjection = new LevelProjection();
    private final Matrix4f farProjection = new Matrix4f();
    private final Matrix4f farViewProjection = new Matrix4f();
    private final Matrix4f gameViewProjection = new Matrix4f();
    private final FrustumIntersection drawFrustum = new FrustumIntersection();
    private final TurnMargin turnMargin = new TurnMargin();
    private final TreeManager tree;
    private final FarDraw draw;
    private final FarShadow shadow;

    private IndirectCommands indirect;
    @SuppressWarnings("NullAway.Init")
    private CompositeFog fog;
    @SuppressWarnings("NullAway.Init")
    private Vector4fc fogColor;
    @SuppressWarnings("NullAway.Init")
    private volatile MeshService meshes;
    private RenderList renderList = RenderList.EMPTY;
    private @Nullable TreeBatch uploading;
    private @Nullable GameFrame game;
    private int uploaded;
    private long frames;
    private boolean framePending = true;
    private boolean farFrame;
    private boolean terrainDrawn;
    private boolean stopped;

    private FarRenderer(Gpu gpu, DepthConvention depth, DimensionRuntime runtime, ClientBakery baking,
            ModelPublisher models, GeometryArena arena, FarTarget target, FarFrame frame, NearMaskPass mask,
            NearSectionTable nearSections, OpaquePass opaque, TranslucentPass translucent, CompositePass composite,
            FarOverlays overlays, IndirectCommands indirect, int levelHeight, int nodeCapacity) {
        this.gpu = gpu;
        this.levelHeight = levelHeight;
        this.runtime = runtime;
        this.baking = baking;
        this.models = models;
        this.arena = arena;
        this.target = target;
        this.frame = frame;
        this.mask = mask;
        this.nearSections = nearSections;
        this.opaque = opaque;
        this.translucent = translucent;
        this.composite = composite;
        this.overlays = overlays;
        this.indirect = indirect;
        draw = new FarDraw(gpu, depth, baking.variantDraw(), arena, models, frame, nearSections, target,
                overlays.beams());
        shadow = FarShadow.create(gpu);
        tree = TreeManager.start(new FarBuilds(() -> meshes),
                new TreeExtent(runtime.frame(), Math.ceilDiv(levelHeight, FarDistance.BLOCKS_PER_TOP_LEVEL_CELL),
                        runtime.lowestStoredLevel()), nodeCapacity);
    }

    public static FarStart start(Minecraft client, Gpu gpu, EminusInstance instance,
            DimensionRuntime runtime, int levelHeight, Settings settings, long replacedArenaBytes,
            PackBlocks packBlocks) {
        gpu.assertRenderThread();

        Texture main = gpu.mainColor();
        FarSizing sizing = FarSizing.of(client, gpu, gpu.capabilities().freeBytes(), replacedArenaBytes,
                runtime.lowestStoredLevel(), settings, levelHeight);
        BackendSupport support = BackendCheck.run(gpu, sizing.arenaBytes());
        GeometryArena arena = GeometryArena.create(gpu, support, sizing.arenaBytes(), sizing.sparse());
        if (arena == null) {
            gpu.close();
            return FarStart.refused(support.reason());
        }

        ClientBakery baking = ClientBakery.start(client, packBlocks);
        NearMaskPass mask = NearMaskPass.create(gpu, FarTarget.COLOR_FORMAT, support.depth(),
                CompositePass.DEPTH_BIAS);
        OpaquePass opaque = OpaquePass.create(gpu, support.depth(), baking.variantDraw());
        TranslucentPass translucent = TranslucentPass.create(gpu, support.depth(), baking.variantDraw());
        CompositePass composite = CompositePass.create(gpu, support.depth());
        FarOverlays overlays = FarOverlays.create(gpu, support.depth());
        String refusal = FarPrograms.refusal(mask, opaque, translucent, composite, overlays);
        if (refusal != null) {
            return refused(refusal, gpu, arena, baking, mask, composite, overlays);
        }

        FarRenderer renderer = new FarRenderer(gpu, support.depth(), runtime, baking,
                ModelPublisher.start(gpu, baking.bakery(), atlasFirstMip(settings.lodQuality())), arena,
                FarTarget.create(gpu, Objects.requireNonNull(support.depthFormat(), "an accepted backend names one"),
                        main.width(), main.height()), FarFrame.create(gpu),
                mask, NearSectionTable.create(gpu), opaque, translucent, composite, overlays,
                IndirectCommands.create(gpu, START_COMMANDS), levelHeight, sizing.nodeCapacity());
        wire(renderer, client, runtime, instance, baking);
        return FarStart.started(renderer);
    }

    private static FarStart refused(String refusal, Gpu gpu, GeometryArena arena, ClientBakery baking,
            NearMaskPass mask, CompositePass composite, FarOverlays overlays) {
        Eminus.LOGGER.warn("Renderer disabled: {}", refusal);
        baking.stop();
        overlays.close();
        composite.close();
        mask.close();
        arena.close();
        gpu.close();
        return FarStart.refused(refusal);
    }

    private static void wire(FarRenderer renderer, Minecraft client, DimensionRuntime runtime,
            EminusInstance instance, ClientBakery baking) {
        renderer.meshes = MeshWiring.service(client, runtime, instance, baking, renderer.tree);
        runtime.listenTo(renderer.tree);
        Eminus.LOGGER.info("Far renderer started for {}", runtime.identity().dimension());
    }

    public long arenaBytes() {
        return arena.committedBytes();
    }

    public boolean seeThroughLeaves() {
        return baking.seeThroughLeaves();
    }

    public GrassAndFlowers grassAndFlowers() {
        return baking.grassAndFlowers();
    }

    public static boolean recreates(Settings built, Settings updated) {
        return !meshed(built.lodQuality()).equals(meshed(updated.lodQuality()));
    }

    public static boolean resizes(Settings built, Settings updated) {
        return built.farRenderChunks() != updated.farRenderChunks() || built.detailDistance() != updated.detailDistance();
    }

    public void resize(Minecraft client, Settings settings) {
        gpu.assertRenderThread();
        FarSizing sizing = FarSizing.of(client, gpu, gpu.freeBytes(), arena.committedBytes(),
                runtime.lowestStoredLevel(), settings, levelHeight);
        if (sizing.arenaBytes() != ArenaSizing.REFUSED) {
            arena.resize(sizing.arenaBytes());
        }
        tree.resize(sizing.nodeCapacity());
    }

    public void retexture(LodQuality quality) {
        gpu.assertRenderThread();
        int firstMip = atlasFirstMip(quality);
        if (firstMip != models.atlas().firstMip()) {
            models.retexture(firstMip);
        }
    }

    private static LodQuality meshed(LodQuality quality) {
        return quality.withFullTextures(true);
    }

    private static int atlasFirstMip(LodQuality quality) {
        return Integer.getInteger(ATLAS_FIRST_MIP_PROPERTY, quality.atlasFirstMip());
    }

    public void captureLevelProjection(Matrix4fc levelProjection, Matrix4fc cameraProjection) {
        this.levelProjection.capture(levelProjection, cameraProjection);
    }

    public boolean detailLimited() {
        return !stopped && (arena.pressure() || tree.horizonBounded());
    }

    public boolean covers(GameFog gameFog, int renderDistanceChunks) {
        if (stopped) {
            return false;
        }

        return !CompositeFog.skipped(gameFog.environmentalEnd(), renderDistanceChunks * FarDistance.BLOCKS_PER_CHUNK);
    }

    public void frame(Minecraft client) {
        if (!stepped(client) || game == null) {
            return;
        }

        Texture main = gpu.mainColor();
        Texture mainDepth = gpu.mainDepth();
        if (farFrame && (terrainDrawn || overlays.drawsAny())) {
            drawMask();
            Texture lightmap = gpu.lightmap();
            Sampler atlas = FarQuads.atlasSampler(game.terrainFilter());
            opaque.draw(target, arena, models, lightmap, atlas, indirect.buffer(),
                    terrainDrawn ? commands.solidCount() : 0, terrainDrawn ? commands.opaqueCount() : 0,
                    frame.buffer(), nearSections.texels());
            if (terrainDrawn) {
                translucent.draw(target, arena, models, lightmap, atlas, indirect.buffer(), commands.opaqueCount(),
                        commands.translucentCount(), frame.buffer(), nearSections.texels());
            }
            overlays.drawFar(target, farViewProjection, game);
            composite.draw(target, main, mainDepth, farViewProjection, gameViewProjection, fog, fogColor);
        }
        overlays.drawNear(main, mainDepth, gameViewProjection, game, NearPlane.blocks(game.renderDistance()));
    }

    public @Nullable FarDraw packFrame(Minecraft client) {
        if (!stepped(client) || !terrainDrawn) {
            return null;
        }

        drawMask();
        return draw;
    }

    public FarDraw farDraw() {
        return draw;
    }

    public @Nullable FarShadow shadowFrame(Minecraft client, Matrix4fc shadowView, Matrix4fc shadowProjection) {
        if (!stepped(client) || !terrainDrawn || game == null) {
            return null;
        }

        return shadow.write(order.meshes(), order.translucent(), renderList::borderFaces, arena, runtime.frame(),
                game, models.atlas(), nearSections.sections(), farViewProjection, shadowView,
                shadowProjection) ? shadow : null;
    }

    public void newFrame() {
        framePending = true;
    }

    private void drawMask() {
        mask.draw(target.depth(), target.color(), gpu.mainDepth(), farViewProjection, gameViewProjection);
    }

    // The shadow pass runs before the main pass: a second step would upload a second batch and post a second walk.
    private boolean stepped(Minecraft client) {
        gpu.assertRenderThread();
        if (stopped) {
            return false;
        }

        if (framePending) {
            framePending = false;
            farFrame = false;
            terrainDrawn = false;
            stepFrame(client);
        }
        return true;
    }

    private void stepFrame(Minecraft client) {
        uploadBatch();
        Texture main = gpu.mainColor();
        target.resize(main.width(), main.height());

        GameFrame game = GameFrames.read(client);
        this.game = game;
        project(game, main);
        Settings settings = SettingsService.get().settings();
        postWalk(client, game, main, settings);

        ClientLevel level = Objects.requireNonNull(client.level, "a far frame steps with a level");
        overlays.update(runtime, level, game);
        updateFog(game, level, settings);
        if (fog.skip()) {
            return;
        }

        farFrame = true;
        order.update(renderList, runtime.frame(), game.eyeX(), game.eyeY(), game.eyeZ());
        if (!writeCommands(game)) {
            return;
        }

        fillNearSections(client, level, game);
        writeFrame(game, settings);
        terrainDrawn = true;
    }

    private void uploadBatch() {
        TreeBatch batch = tree.batches().peek();
        if (batch != null) {
            upload(batch);
        }

        models.publish();
    }

    private void project(GameFrame game, Texture main) {
        projection.projection(NearPlane.blocks(game.renderDistance()), game.fov(), levelProjection.fold(),
                main.width(), main.height(), farProjection);
        farViewProjection.set(farProjection).mul(game.viewRotation());
        FarProjection.gameViewProjection(levelProjection.projection(), game.viewRotation(), gameViewProjection);
    }

    private void postWalk(Minecraft client, GameFrame game, Texture main, Settings settings) {
        frames++;
        Quaternionf rotation = game.viewRotation().getNormalizedRotation(new Quaternionf());
        float margin = turnMargin.frame(rotation);
        Matrix4f walkViewProjection = projection.walkViewProjection(NearPlane.blocks(game.renderDistance()),
                game.fov(), margin, levelProjection.fold(), game.viewRotation(), main.width(), main.height(),
                new Matrix4f());

        tree.frame(new CameraFrame(game.eyeX(), game.eyeY(), game.eyeZ(), walkViewProjection,
                FarProjection.focalPixels(client.options.fov().get(), main.height()),
                FarProjection.focalPixels(game.fov(), main.height()), effectiveChunks(game, settings),
                settings.detailDistance().pixels(), arena.pressure(), frames, rotation, margin));
    }

    private static int effectiveChunks(GameFrame game, Settings settings) {
        return FarDistance.effective(settings.farRenderChunks(), game.renderDistance());
    }

    private void updateFog(GameFrame game, ClientLevel level, Settings settings) {
        GameFog gameFog = game.fog();
        int renderDistance = game.renderDistance();
        float nearBlocks = renderDistance * FarDistance.BLOCKS_PER_CHUNK;
        int keptChunks = renderDistance + ClientSession.CLIENT_EXTRA_CHUNKS;
        float reachBlocks = NearReach.blocks(keptChunks, game.eyeY(), level.getMinY(),
                level.getMinY() + level.getHeight());
        fog = CompositeFog.of(settings.fog(), settings.fade(), gameFog.environmentalStart(),
                gameFog.environmentalEnd(), nearBlocks, reachBlocks, NearReach.horizontalBlocks(keptChunks),
                effectiveChunks(game, settings));
        fogColor = gameFog.color();
    }

    private boolean writeCommands(GameFrame game) {
        commands.write(order.meshes(), order.translucent(), renderList::borderFaces, arena, runtime.frame(),
                drawFrustum.set(farViewProjection),
                NearBox.around(NearSections.section(Mth.floor(game.eyeX())),
                        NearSections.section(Mth.floor(game.eyeZ())),
                        game.renderDistance() + ClientSession.CLIENT_EXTRA_CHUNKS),
                game.eyeX(), game.eyeY(), game.eyeZ());
        if (commands.count() == 0) {
            return false;
        }

        if (indirect.capacity() < commands.capacity()) {
            indirect.close();
            indirect = IndirectCommands.create(gpu, commands.capacity());
        }

        indirect.write(commands);
        return true;
    }

    private void writeFrame(GameFrame game, Settings settings) {
        frame.write(farViewProjection, game.viewRotation(), runtime.frame().minBlockY(),
                models.atlas(), nearSections.sections(), game.shade(), game.smoothLighting(), game.rgss(),
                game.anisotropy(), CameraOrigin.of(game.eyeX(), game.eyeY(), game.eyeZ()));
        draw.frame(indirect.buffer(), commands.opaqueCount(), commands.translucentCount(), gpu.lightmap(),
                FarQuads.atlasSampler(game.terrainFilter()), farProjection, game.viewRotation(),
                FarDistance.chunksToBlocks(effectiveChunks(game, settings)));
    }

    private void upload(TreeBatch batch) {
        if (batch != uploading) {
            uploading = batch;
            uploaded = 0;
            arena.evict(batch.evicted());
        }

        List<CellMesh> batchMeshes = batch.meshes();
        uploaded = arena.accept(batchMeshes, uploaded);
        if (uploaded < batchMeshes.size()) {
            return;
        }

        takeRenderList(batch);
    }

    private void takeRenderList(TreeBatch batch) {
        RenderList walked = batch.renderList();
        if (walked != null) {
            renderList = walked;
            CameraFrame walkedWith = walked.walkedWith();
            if (walkedWith != null) {
                turnMargin.drawn(walkedWith);
            }
        }

        tree.batches().take();
        uploading = null;
    }

    public CompletableFuture<FarState> state() {
        gpu.assertRenderThread();
        String dimension = runtime.identity().dimension();
        ArenaState arenaState = arena.state();
        IngestService ingest = runtime.ingest();
        int ingestQueued = ingest == null ? 0 : ingest.queued();
        int pendingBlockChanges = ingest == null ? 0 : ingest.pendingBlockChanges();

        return tree.snapshot().thenApply(treeState ->
                new FarState(dimension, arenaState, treeState, ingestQueued, pendingBlockChanges));
    }

    public CompletableFuture<List<long[]>> describe(int blockX, int blockY, int blockZ) {
        gpu.assertRenderThread();
        List<long[]> rows = new ArrayList<>(DetailLevel.COUNT);

        for (int level = DetailLevel.MIN; level <= DetailLevel.MAX; level++) {
            long key = runtime.frame().keyAt(level, blockX, blockY, blockZ);
            MeshSlot slot = arena.slot(key);
            long[] row = new long[NodeRow.WIDTH];
            row[NodeRow.LEVEL] = level;
            row[NodeRow.CELL_X] = CellKey.x(key);
            row[NodeRow.CELL_Y] = CellKey.y(key);
            row[NodeRow.CELL_Z] = CellKey.z(key);
            row[NodeRow.SLOT_PRESENT] = slot == null ? 0 : 1;
            row[NodeRow.SLOT_QUADS] = slot == null ? 0 : slot.quads();
            row[NodeRow.SLOT_SEGMENT] = slot == null ? -1 : slot.segment();
            rows.add(row);
        }

        return tree.describe(rows);
    }

    public CompletableFuture<Map<String, Long>> churn() {
        return tree.churn();
    }

    @Override
    public void close() {
        gpu.assertRenderThread();
        stopped = true;
        runtime.stopListening();
        tree.stop();
        meshes.drop();
        baking.stop();

        indirect.close();
        shadow.close();
        overlays.close();
        composite.close();
        mask.close();
        nearSections.close();
        frame.close();
        target.close();
        models.close();
        arena.close();
        gpu.close();
        Eminus.LOGGER.info("Far renderer stopped for {}", runtime.identity().dimension());
    }

    private void fillNearSections(Minecraft client, ClientLevel level, GameFrame game) {
        int renderDistance = game.renderDistance();
        nearSections.fill(client.levelRenderer, game.sectionFadeMillis(), order.meshes(), runtime.frame(),
                NearSections.section(Mth.floor(game.eyeX())), NearSections.section(Mth.floor(game.eyeY())),
                NearSections.section(Mth.floor(game.eyeZ())), renderDistance,
                renderDistance + ClientSession.CLIENT_EXTRA_CHUNKS, level.getMinSectionY(), level.getSectionsCount());
    }
}
