package com.eminus.compat.sodium.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.eminus.Eminus;
import com.eminus.client.settings.CustomLodScreen;
import com.eminus.client.settings.OptionCost;
import com.eminus.client.settings.SettingsText;
import com.eminus.client.settings.SliderPositions;
import com.eminus.compat.iris.IrisShaderPack;
import com.eminus.compat.sodium.settings.mixin.VideoSettingsScreenAccessor;
import com.eminus.settings.FarDistance;
import com.eminus.settings.Settings;
import com.eminus.settings.SettingsService;
import com.eminus.settings.ShaderPackLod;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class SodiumSettingsPage implements ConfigEntryPoint {
    private static final String INGESTION_ID = "ingestion";
    private static final String LOD_QUALITY_ID = "lod_quality";
    private static final String CUSTOM_LOD_ID = "custom_lod";
    private static final String FAR_RENDER_CHUNKS_ID = "far_render_cells";
    private static final String WORKER_THREADS_ID = "worker_threads";
    private static final String DETAIL_DISTANCE_ID = "detail_distance";
    private static final String FOG_ID = "fog";
    private static final String FADE_ID = "fade";
    private static final String SHADER_PACK_LOD_ID = "shader_pack_lod";
    private static final String LOD_ANIMATIONS_ID = "lod_animations";

    private static final int STEP = 1;

    private final List<UnaryOperator<Settings>> edits = new ArrayList<>();

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!SodiumSettingsMixinPlugin.settingsPresent()) {
            return;
        }

        Settings defaults = Settings.defaults();
        OptionGroupBuilder general = general(builder, defaults);
        OptionGroupBuilder appearance = appearance(builder, defaults);
        OptionGroupBuilder building = building(builder, defaults);
        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable(SettingsText.TITLE_KEY))
                .addOptionGroup(general)
                .addOptionGroup(appearance)
                .addOptionGroup(building);
        if (IrisShaderPack.installed()) {
            page.addOptionGroup(shaderPacks(builder, defaults));
        }

        builder.registerOwnModOptions().addPage(page);
    }

    private OptionGroupBuilder general(ConfigBuilder builder, Settings defaults) {
        return builder.createOptionGroup()
                .setName(Component.translatable(SettingsText.GENERAL_CATEGORY_KEY))
                .addOption(builder.createIntegerOption(id(LOD_QUALITY_ID))
                        .setName(Component.translatable(SettingsText.LOD_QUALITY_KEY))
                        .setTooltip(position -> SettingsText.withRestart(SettingsText.lodChoiceHint(),
                                OptionCost.LOD_QUALITY))
                        .setStorageHandler(this::save)
                        .setRange(SliderPositions.FIRST, SliderPositions.LAST_LOD_CHOICE, STEP)
                        .setValueFormatter(position -> SettingsText.lodChoice(SliderPositions.lodChoice(position)))
                        .setDefaultValue(SliderPositions.lodChoicePosition(defaults.lodChoice()))
                        .setBinding(position -> edit(settings -> settings.withLodChoice(
                                        SliderPositions.lodChoice(position))),
                                () -> SliderPositions.lodChoicePosition(current().lodChoice())))
                .addOption(builder.createExternalButtonOption(id(CUSTOM_LOD_ID))
                        .setName(Component.translatable(SettingsText.CUSTOM_LOD_KEY))
                        .setTooltip(SettingsText.hint(SettingsText.CUSTOM_LOD_KEY))
                        .setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(
                                CustomLodScreen.withOwnDraft(screen, SodiumSettingsPage::returnTo))))
                .addOption(builder.createIntegerOption(id(FAR_RENDER_CHUNKS_ID))
                        .setName(Component.translatable(SettingsText.FAR_RENDER_CHUNKS_KEY))
                        .setTooltip(SettingsText.withRestart(SettingsText.hint(SettingsText.FAR_RENDER_CHUNKS_KEY),
                                OptionCost.LOD_DISTANCE))
                        .setStorageHandler(this::save)
                        .setRange(FarDistance.minimumChunks(Minecraft.getInstance().options.getEffectiveRenderDistance()),
                                Settings.MAX_FAR_RENDER_CHUNKS, STEP)
                        .setValueFormatter(SettingsText::farRenderDistance)
                        .setDefaultValue(defaults.farRenderChunks())
                        .setBinding(value -> edit(settings -> settings.withFarRenderChunks(value)),
                                () -> Math.max(current().farRenderChunks(), FarDistance.minimumChunks(
                                Minecraft.getInstance().options.getEffectiveRenderDistance()))))
                .addOption(builder.createIntegerOption(id(DETAIL_DISTANCE_ID))
                        .setName(Component.translatable(SettingsText.DETAIL_DISTANCE_KEY))
                        .setTooltip(position -> SettingsText.detailDistanceHint())
                        .setStorageHandler(this::save)
                        .setRange(SliderPositions.FIRST, SliderPositions.LAST_DETAIL_DISTANCE, STEP)
                        .setValueFormatter(position -> SettingsText.detailDistance(
                                SliderPositions.detailDistance(position)))
                        .setDefaultValue(SliderPositions.detailDistancePosition(defaults.detailDistance()))
                        .setBinding(position -> edit(settings -> settings.withDetailDistance(
                                        SliderPositions.detailDistance(position))),
                                () -> SliderPositions.detailDistancePosition(current().detailDistance())))
                .addOption(builder.createBooleanOption(id(INGESTION_ID))
                        .setName(Component.translatable(SettingsText.INGESTION_KEY))
                        .setTooltip(value -> SettingsText.toggleHint(SettingsText.INGESTION_KEY))
                        .setStorageHandler(this::save)
                        .setDefaultValue(defaults.ingestion())
                        .setBinding(value -> edit(settings -> settings.withIngestion(value)),
                                () -> current().ingestion()));
    }

    private OptionGroupBuilder appearance(ConfigBuilder builder, Settings defaults) {
        return builder.createOptionGroup()
                .setName(Component.translatable(SettingsText.APPEARANCE_CATEGORY_KEY))
                .addOption(builder.createBooleanOption(id(FOG_ID))
                        .setName(Component.translatable(SettingsText.FOG_KEY))
                        .setTooltip(value -> SettingsText.toggleHint(SettingsText.FOG_KEY))
                        .setStorageHandler(this::save)
                        .setDefaultValue(defaults.fog())
                        .setBinding(value -> edit(settings -> settings.withFog(value)),
                                () -> current().fog()))
                .addOption(builder.createBooleanOption(id(FADE_ID))
                        .setName(Component.translatable(SettingsText.FADE_KEY))
                        .setTooltip(value -> SettingsText.toggleHint(SettingsText.FADE_KEY))
                        .setStorageHandler(this::save)
                        .setDefaultValue(defaults.fade())
                        .setBinding(value -> edit(settings -> settings.withFade(value)),
                                () -> current().fade()));
    }

    private OptionGroupBuilder building(ConfigBuilder builder, Settings defaults) {
        return builder.createOptionGroup()
                .setName(Component.translatable(SettingsText.BUILDING_CATEGORY_KEY))
                .addOption(builder.createIntegerOption(id(WORKER_THREADS_ID))
                        .setName(Component.translatable(SettingsText.WORKER_THREADS_KEY))
                        .setTooltip(SettingsText.hint(SettingsText.WORKER_THREADS_KEY))
                        .setStorageHandler(this::save)
                        .setRange(Settings.MIN_WORKER_THREADS, Settings.MAX_WORKER_THREADS, STEP)
                        .setValueFormatter(SodiumSettingsPage::number)
                        .setDefaultValue(defaults.workerThreads())
                        .setBinding(value -> edit(settings -> settings.withWorkerThreads(value)),
                                () -> current().workerThreads()));
    }

    private OptionGroupBuilder shaderPacks(ConfigBuilder builder, Settings defaults) {
        return builder.createOptionGroup()
                .setName(Component.translatable(SettingsText.SHADER_PACKS_CATEGORY_KEY))
                .addOption(builder.createEnumOption(id(SHADER_PACK_LOD_ID), ShaderPackLod.class)
                        .setName(Component.translatable(SettingsText.SHADER_PACK_LOD_KEY))
                        .setTooltip(value -> SettingsText.withRestart(SettingsText.shaderPackLodHint(),
                                OptionCost.SHADER_PACK_LOD))
                        .setStorageHandler(this::save)
                        .setElementNameProvider(SettingsText::shaderPackLod)
                        .setDefaultValue(defaults.shaderPackLod())
                        .setBinding(value -> edit(settings -> settings.withShaderPackLod(value)),
                                () -> current().shaderPackLod()))
                .addOption(builder.createBooleanOption(id(LOD_ANIMATIONS_ID))
                        .setName(Component.translatable(SettingsText.LOD_ANIMATIONS_KEY))
                        .setTooltip(value -> SettingsText.withRestart(
                                SettingsText.toggleHint(SettingsText.LOD_ANIMATIONS_KEY), OptionCost.LOD_ANIMATIONS))
                        .setImpact(impact(OptionCost.LOD_ANIMATIONS))
                        .setStorageHandler(this::save)
                        .setDefaultValue(defaults.lodAnimations())
                        .setBinding(value -> edit(settings -> settings.withLodAnimations(value)),
                                () -> current().lodAnimations()));
    }

    private void edit(UnaryOperator<Settings> edit) {
        edits.add(edit);
    }

    private void save() {
        Settings updated = current();
        for (UnaryOperator<Settings> edit : edits) {
            updated = edit.apply(updated);
        }

        edits.clear();
        SettingsService.get().update(updated);
    }

    private static void returnTo(Screen sodium) {
        Minecraft client = Minecraft.getInstance();
        try {
            OptionPage page = ownPage();
            boolean gated = sodium instanceof VideoSettingsScreenAccessor;
            Eminus.LOGGER.info("[eminus/sodium-settings] return path={} accessor={} page_found={}",
                    gated && page != null ? "own_page" : "first_page", gated, page != null);
            if (sodium instanceof VideoSettingsScreenAccessor accessor && page != null) {
                client.gui.setScreen(VideoSettingsScreen.createScreen(accessor.eminus$prevScreen(), page));
                return;
            }

            ConfigManager.CONFIG.resetAllOptionsFromBindings();
        } catch (LinkageError changed) {
            Eminus.LOGGER.warn("Sodium's screen could not be reopened on Eminus's page after Custom LOD Quality;"
                    + " its options show as they were until it is opened again: {}", changed.toString());
        }

        client.gui.setScreen(sodium);
    }

    private static @Nullable OptionPage ownPage() {
        for (ModOptions mod : ConfigManager.CONFIG.getModOptions()) {
            if (Eminus.MODID.equals(mod.configId())) {
                for (Page page : mod.pages()) {
                    if (page instanceof OptionPage options) {
                        return options;
                    }
                }
            }
        }

        return null;
    }

    private static Settings current() {
        return SettingsService.get().settings();
    }

    private static OptionImpact impact(OptionCost cost) {
        return switch (Objects.requireNonNull(cost.impact())) {
            case LOW -> OptionImpact.LOW;
            case MEDIUM -> OptionImpact.MEDIUM;
            case HIGH -> OptionImpact.HIGH;
        };
    }

    private static Component number(int value) {
        return Component.literal(String.valueOf(value));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Eminus.MODID, path);
    }
}
