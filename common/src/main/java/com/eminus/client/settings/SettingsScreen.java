package com.eminus.client.settings;

import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import static com.eminus.client.settings.SettingsText.APPEARANCE_CATEGORY_KEY;
import static com.eminus.client.settings.SettingsText.BUILDING_CATEGORY_KEY;
import static com.eminus.client.settings.SettingsText.CUSTOM_LOD_KEY;
import static com.eminus.client.settings.SettingsText.DETAIL_DISTANCE_KEY;
import static com.eminus.client.settings.SettingsText.FADE_KEY;
import static com.eminus.client.settings.SettingsText.FAR_RENDER_CHUNKS_KEY;
import static com.eminus.client.settings.SettingsText.FOG_KEY;
import static com.eminus.client.settings.SettingsText.GENERAL_CATEGORY_KEY;
import static com.eminus.client.settings.SettingsText.INGESTION_KEY;
import static com.eminus.client.settings.SettingsText.LOD_ANIMATIONS_KEY;
import static com.eminus.client.settings.SettingsText.LOD_QUALITY_KEY;
import static com.eminus.client.settings.SettingsText.SHADER_PACKS_CATEGORY_KEY;
import static com.eminus.client.settings.SettingsText.SHADER_PACK_LOD_KEY;
import static com.eminus.client.settings.SettingsText.TITLE_KEY;
import static com.eminus.client.settings.SettingsText.WORKER_THREADS_KEY;
import static com.eminus.client.settings.SettingsText.tooltip;

import com.eminus.compat.iris.IrisShaderPack;
import com.eminus.settings.DetailDistance;
import com.eminus.settings.LodChoice;
import com.eminus.settings.FarDistance;
import com.eminus.settings.Settings;
import com.eminus.settings.SettingsDraft;
import com.eminus.settings.SettingsService;
import com.eminus.settings.ShaderPackLod;
import com.mojang.serialization.Codec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends DraftOptionsScreen {
    private static final boolean APPLY_ON_RELEASE = false;
    private static final boolean STEPPED = true;

    private final OptionInstance<Boolean> ingestion;
    private final OptionInstance<LodChoice> lodChoice;
    private final OptionInstance<Integer> farRenderChunks;
    private final OptionInstance<Integer> workerThreads;
    private final OptionInstance<DetailDistance> detailDistance;
    private final OptionInstance<Boolean> fog;
    private final OptionInstance<Boolean> fade;
    private final OptionInstance<ShaderPackLod> shaderPackLod;
    private final OptionInstance<Boolean> lodAnimations;

    public SettingsScreen(Screen lastScreen) {
        super(lastScreen, Component.translatable(TITLE_KEY), SettingsDraft.of(SettingsService.get()), HOLDS_DRAFT,
                DraftOptionsScreen::open);

        Settings settings = this.draft.settings();

        this.ingestion = OptionInstance.createBoolean(INGESTION_KEY, tooltip(SettingsText.toggleHint(INGESTION_KEY)),
                settings.ingestion(), value -> this.editFromOptions());
        this.lodChoice = new OptionInstance<>(LOD_QUALITY_KEY,
                tooltip(SettingsText.withCost(SettingsText.lodChoiceHint(), OptionCost.LOD_QUALITY)),
                (caption, value) -> Options.genericValueLabel(caption, SettingsText.lodChoice(value)),
                coarseToFine(SliderPositions.LAST_LOD_CHOICE, SliderPositions::lodChoice,
                        SliderPositions::lodChoicePosition),
                settings.lodChoice(), value -> this.editFromOptions());
        this.farRenderChunks = new OptionInstance<>(FAR_RENDER_CHUNKS_KEY,
                tooltip(SettingsText.withCost(SettingsText.hint(FAR_RENDER_CHUNKS_KEY), OptionCost.LOD_DISTANCE)),
                (caption, value) -> Options.genericValueLabel(caption, SettingsText.farRenderDistance(value)),
                new OptionInstance.IntRange(FarDistance.minimumChunks(Minecraft.getInstance().options.getEffectiveRenderDistance()),
                        Settings.MAX_FAR_RENDER_CHUNKS, APPLY_ON_RELEASE),
                Math.max(settings.farRenderChunks(),
                        FarDistance.minimumChunks(Minecraft.getInstance().options.getEffectiveRenderDistance())), value -> this.editFromOptions());
        this.workerThreads = new OptionInstance<>(WORKER_THREADS_KEY, tooltip(WORKER_THREADS_KEY),
                (caption, value) -> Options.genericValueLabel(caption, value),
                new OptionInstance.IntRange(Settings.MIN_WORKER_THREADS, Settings.MAX_WORKER_THREADS,
                        APPLY_ON_RELEASE),
                settings.workerThreads(), value -> this.editFromOptions());
        this.detailDistance = new OptionInstance<>(DETAIL_DISTANCE_KEY,
                value -> Tooltip.create(SettingsText.detailDistanceHint()),
                (caption, value) -> Options.genericValueLabel(caption, SettingsText.detailDistance(value)),
                coarseToFine(SliderPositions.LAST_DETAIL_DISTANCE, SliderPositions::detailDistance,
                        SliderPositions::detailDistancePosition),
                settings.detailDistance(), value -> this.editFromOptions());
        this.fog = OptionInstance.createBoolean(FOG_KEY, tooltip(SettingsText.toggleHint(FOG_KEY)), settings.fog(),
                value -> this.editFromOptions());
        this.fade = OptionInstance.createBoolean(FADE_KEY, tooltip(SettingsText.toggleHint(FADE_KEY)), settings.fade(),
                value -> this.editFromOptions());
        this.shaderPackLod = new OptionInstance<>(SHADER_PACK_LOD_KEY,
                tooltip(SettingsText.withCost(SettingsText.shaderPackLodHint(), OptionCost.SHADER_PACK_LOD)),
                (caption, value) -> SettingsText.shaderPackLod(value),
                new OptionInstance.Enum<>(List.of(ShaderPackLod.values()),
                        Codec.STRING.xmap(key -> ShaderPackLod.fromKey(key).orElse(Settings.DEFAULT_SHADER_PACK_LOD),
                                ShaderPackLod::key)),
                settings.shaderPackLod(), value -> this.editFromOptions());
        this.lodAnimations = OptionInstance.createBoolean(LOD_ANIMATIONS_KEY,
                tooltip(SettingsText.withCost(SettingsText.toggleHint(LOD_ANIMATIONS_KEY), OptionCost.LOD_ANIMATIONS)),
                settings.lodAnimations(),
                value -> this.editFromOptions());
    }

    @Override
    protected void addOptions() {
        Button customLodButton = Button.builder(Component.translatable(CUSTOM_LOD_KEY),
                        button -> this.minecraft.gui.setScreen(CustomLodScreen.sharingDraft(this)))
                .tooltip(Tooltip.create(SettingsText.hint(CUSTOM_LOD_KEY)))
                .build();
        this.addHeader(GENERAL_CATEGORY_KEY);
        this.optionsList().addSmall(this.lodChoice.createButton(this.options), this.lodChoice, customLodButton);
        this.optionsList().addBig(this.farRenderChunks);
        this.optionsList().addBig(this.detailDistance);
        this.optionsList().addBig(this.ingestion);
        this.addHeader(APPEARANCE_CATEGORY_KEY);
        this.optionsList().addBig(this.fog);
        this.optionsList().addBig(this.fade);
        this.addHeader(BUILDING_CATEGORY_KEY);
        this.optionsList().addBig(this.workerThreads);
        if (IrisShaderPack.installed()) {
            this.addHeader(SHADER_PACKS_CATEGORY_KEY);
            this.optionsList().addBig(this.shaderPackLod);
            this.optionsList().addBig(this.lodAnimations);
        }
    }

    @Override
    protected void draftChanged() {
        super.draftChanged();
        LodChoice choice = this.draft.settings().lodChoice();
        if (this.lodChoice.get() != choice) {
            this.lodChoice.set(choice);
            this.resetOption(this.lodChoice);
        }
    }

    private void editFromOptions() {
        this.edit(settings -> new Settings(
                this.ingestion.get(),
                this.lodChoice.get(),
                settings.customLod(),
                this.farRenderChunks.get(),
                this.workerThreads.get(),
                this.detailDistance.get(),
                this.fog.get(),
                this.fade.get(),
                this.shaderPackLod.get(),
                this.lodAnimations.get()));
    }

    private static <T> OptionInstance.SliderableValueSet<T> coarseToFine(int lastPosition, IntFunction<T> value,
            ToIntFunction<T> position) {
        return new AppliedOnRelease<>(new OptionInstance.IntRange(SliderPositions.FIRST, lastPosition, APPLY_ON_RELEASE)
                .xmap(value, position, STEPPED));
    }

    // Vanilla's xmap drops the range's apply-on-release and applies every step while the slider is dragged.
    private record AppliedOnRelease<T>(OptionInstance.SliderableValueSet<T> steps)
            implements OptionInstance.SliderableValueSet<T> {
        @Override
        public double toSliderValue(T value) {
            return steps.toSliderValue(value);
        }

        @Override
        public T fromSliderValue(double slider) {
            return steps.fromSliderValue(slider);
        }

        @Override
        public Optional<T> next(T current) {
            return steps.next(current);
        }

        @Override
        public Optional<T> previous(T current) {
            return steps.previous(current);
        }

        @Override
        public Optional<T> validateValue(T value) {
            return steps.validateValue(value);
        }

        @Override
        public Codec<T> codec() {
            return steps.codec();
        }

        @Override
        public boolean applyValueImmediately() {
            return APPLY_ON_RELEASE;
        }
    }
}
