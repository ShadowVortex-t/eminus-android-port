package com.eminus.client.settings;

import java.util.ArrayList;
import java.util.List;

import com.eminus.client.render.far.FarRenderer;
import com.eminus.client.session.ClientSession;
import com.eminus.settings.CornerOcclusion;
import com.eminus.settings.DetailDistance;
import com.eminus.settings.FarDistance;
import com.eminus.settings.GrassAndFlowers;
import com.eminus.settings.LodChoice;
import com.eminus.settings.LodQuality;
import com.eminus.settings.ShaderPackLod;

import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class SettingsText {
    public static final String TITLE_KEY = "gui.eminus.settings.title";
    public static final String INGESTION_KEY = "gui.eminus.settings.ingestion";
    public static final String LOD_QUALITY_KEY = "gui.eminus.settings.lod_quality";
    public static final String FAR_RENDER_CHUNKS_KEY = "gui.eminus.settings.far_render_cells";
    public static final String WORKER_THREADS_KEY = "gui.eminus.settings.worker_threads";
    public static final String DETAIL_DISTANCE_KEY = "gui.eminus.settings.detail_distance";
    public static final String FOG_KEY = "gui.eminus.settings.fog";
    public static final String FADE_KEY = "gui.eminus.settings.fade";
    public static final String SHADER_PACK_LOD_KEY = "gui.eminus.settings.shader_pack_lod";
    public static final String LOD_ANIMATIONS_KEY = "gui.eminus.settings.lod_animations";
    public static final String GENERAL_CATEGORY_KEY = "gui.eminus.settings.category.general";
    public static final String APPEARANCE_CATEGORY_KEY = "gui.eminus.settings.category.appearance";
    public static final String BUILDING_CATEGORY_KEY = "gui.eminus.settings.category.building";
    public static final String SHADER_PACKS_CATEGORY_KEY = "gui.eminus.settings.category.shader_packs";
    public static final String RESOLUTION_CATEGORY_KEY = "gui.eminus.settings.category.resolution";
    public static final String VEGETATION_CATEGORY_KEY = "gui.eminus.settings.category.vegetation";
    public static final String LIGHTING_CATEGORY_KEY = "gui.eminus.settings.category.lighting";
    public static final String CUSTOM_LOD_KEY = "gui.eminus.settings.custom_lod";
    public static final String CUSTOM_LOD_TITLE_KEY = "gui.eminus.settings.custom_lod.title";
    public static final String NEAREST_VOXEL_KEY = "gui.eminus.settings.custom_lod.nearest_voxel";
    public static final String LEAVES_KEY = "gui.eminus.settings.custom_lod.leaves";
    public static final String GRASS_AND_FLOWERS_KEY = "gui.eminus.settings.custom_lod.grass_and_flowers";
    public static final String TEXTURES_KEY = "gui.eminus.settings.custom_lod.textures";
    public static final String CORNER_OCCLUSION_KEY = "gui.eminus.settings.custom_lod.corner_occlusion";

    private static final String HINT_SUFFIX = ".hint";
    private static final String LIMITED_SUFFIX = ".limited";
    private static final String PARAGRAPH = "\n\n";
    private static final String VANILLA_CHUNKS_KEY = "options.chunks";
    private static final String TEXTURES_FULL_SUFFIX = ".full";
    private static final String TEXTURES_HALF_SUFFIX = ".half";
    private static final String VALUE_HINT_KEY = "gui.eminus.settings.value_hint";
    private static final String RESTART_KEY = "gui.eminus.settings.restart.";
    private static final String IMPACT_KEY = "gui.eminus.settings.impact";
    private static final String INTRO = "intro";
    private static final String NOTE = "note";
    private static final String ON = "on";
    private static final String OFF = "off";
    private static final String FULL = "full";
    private static final String HALF = "half";
    private static final ChatFormatting VALUE_STYLE = ChatFormatting.YELLOW;
    private static final ChatFormatting NOTE_STYLE = ChatFormatting.GRAY;

    private record ValueHint(Component name, String key) {
    }

    public static Component hint(String captionKey) {
        return Component.translatable(captionKey + HINT_SUFFIX);
    }

    public static <T> OptionInstance.TooltipSupplier<T> tooltip(String captionKey) {
        return tooltip(hint(captionKey));
    }

    public static <T> OptionInstance.TooltipSupplier<T> tooltip(Component hint) {
        return OptionInstance.cachedConstantTooltip(hint);
    }

    public static Component toggleHint(String captionKey) {
        return valuesHint(captionKey, List.of(new ValueHint(CommonComponents.OPTION_ON, ON),
                new ValueHint(CommonComponents.OPTION_OFF, OFF)));
    }

    public static Component lodChoiceHint() {
        List<ValueHint> values = new ArrayList<>();
        for (LodChoice choice : LodChoice.values()) {
            values.add(new ValueHint(lodChoice(choice), choice.key()));
        }

        return valuesHint(LOD_QUALITY_KEY, values);
    }

    public static Component shaderPackLodHint() {
        List<ValueHint> values = new ArrayList<>();
        for (ShaderPackLod lod : ShaderPackLod.values()) {
            values.add(new ValueHint(shaderPackLod(lod), lod.key()));
        }

        return valuesHint(SHADER_PACK_LOD_KEY, values);
    }

    public static Component texturesHint() {
        return valuesHint(TEXTURES_KEY, List.of(new ValueHint(textures(true), FULL),
                new ValueHint(textures(false), HALF)));
    }

    public static Component grassAndFlowersHint() {
        List<ValueHint> values = new ArrayList<>();
        for (GrassAndFlowers value : GrassAndFlowers.values()) {
            values.add(new ValueHint(grassAndFlowers(value), value.key()));
        }

        return valuesHint(GRASS_AND_FLOWERS_KEY, values);
    }

    public static Component cornerOcclusionHint() {
        List<ValueHint> values = new ArrayList<>();
        for (CornerOcclusion occlusion : CornerOcclusion.values()) {
            values.add(new ValueHint(cornerOcclusion(occlusion), occlusion.key()));
        }

        return valuesHint(CORNER_OCCLUSION_KEY, values);
    }

    private static Component valuesHint(String captionKey, List<ValueHint> values) {
        String prefix = captionKey + HINT_SUFFIX + ".";
        Language language = Language.getInstance();
        MutableComponent hint = Component.empty();
        if (language.has(prefix + INTRO)) {
            hint.append(Component.translatable(prefix + INTRO)).append(PARAGRAPH);
        }

        for (int index = 0; index < values.size(); index++) {
            ValueHint value = values.get(index);
            if (index > 0) {
                hint.append(PARAGRAPH);
            }

            hint.append(Component.translatable(VALUE_HINT_KEY, value.name().copy().withStyle(VALUE_STYLE),
                    Component.translatable(prefix + value.key())));
        }

        if (language.has(prefix + NOTE)) {
            hint.append(PARAGRAPH).append(Component.translatable(prefix + NOTE).withStyle(NOTE_STYLE));
        }

        return hint;
    }

    public static Component withCost(Component hint, OptionCost cost) {
        MutableComponent text = withRestart(hint, cost);
        SettingImpact impact = cost.impact();
        if (impact == null) {
            return text;
        }

        return text.append(PARAGRAPH).append(Component.translatable(IMPACT_KEY,
                Component.translatable(IMPACT_KEY + "." + impact.key()).withStyle(impact.style())));
    }

    public static MutableComponent withRestart(Component hint, OptionCost cost) {
        return Component.empty().append(hint).append(PARAGRAPH)
                .append(Component.translatable(RESTART_KEY + cost.restart().key()).withStyle(NOTE_STYLE));
    }

    public static Component detailDistanceHint() {
        Component hint = hint(DETAIL_DISTANCE_KEY);
        FarRenderer renderer = ClientSession.renderer();
        if (renderer != null && renderer.detailLimited()) {
            hint = Component.empty().append(hint).append(PARAGRAPH)
                    .append(Component.translatable(DETAIL_DISTANCE_KEY + LIMITED_SUFFIX));
        }

        return withRestart(hint, OptionCost.DETAIL_DISTANCE);
    }

    public static Component lodChoice(LodChoice choice) {
        return Component.translatable(LOD_QUALITY_KEY + "." + choice.key());
    }

    public static Component nearestVoxel(int storedLevel) {
        return Component.translatable(NEAREST_VOXEL_KEY + "." + LodQuality.voxelBlocks(storedLevel));
    }

    public static Component textures(boolean full) {
        return Component.translatable(TEXTURES_KEY + (full ? TEXTURES_FULL_SUFFIX : TEXTURES_HALF_SUFFIX));
    }

    public static Component grassAndFlowers(GrassAndFlowers value) {
        return value == GrassAndFlowers.OFF
                ? CommonComponents.OPTION_OFF
                : Component.translatable(GRASS_AND_FLOWERS_KEY + "." + value.key());
    }

    public static Component cornerOcclusion(CornerOcclusion occlusion) {
        return occlusion == CornerOcclusion.OFF
                ? CommonComponents.OPTION_OFF
                : Component.translatable(CORNER_OCCLUSION_KEY + "." + occlusion.key());
    }

    public static Component detailDistance(DetailDistance distance) {
        return Component.translatable(DETAIL_DISTANCE_KEY + "." + distance.key());
    }

    public static Component shaderPackLod(ShaderPackLod lod) {
        return Component.translatable(SHADER_PACK_LOD_KEY + "." + lod.key());
    }

    public static Component farRenderDistance(int chunks) {
        return Component.translatable(VANILLA_CHUNKS_KEY, chunks);
    }

    private SettingsText() {
    }
}
