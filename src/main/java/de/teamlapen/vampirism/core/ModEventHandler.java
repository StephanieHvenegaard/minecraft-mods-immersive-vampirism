package de.teamlapen.vampirism.core;

import com.mojang.datafixers.util.Pair;
import de.teamlapen.lib.lib.util.UtilLib;
import de.teamlapen.lib.lib.util.VersionChecker;
import de.teamlapen.vampirism.VampirismMod;
import de.teamlapen.vampirism.api.VampirismAPI;
import de.teamlapen.vampirism.api.general.BloodConversionRegistry;
import de.teamlapen.vampirism.config.VampirismConfig;
import de.teamlapen.vampirism.entity.converted.VampirismEntityRegistry;
import de.teamlapen.vampirism.entity.factions.FactionPlayerHandler;
import de.teamlapen.vampirism.modcompat.IntegrationsNotifier;
import de.teamlapen.vampirism.network.BloodValuePacket;
import de.teamlapen.vampirism.network.SkillTreePacket;
import de.teamlapen.vampirism.tileentity.TotemHelper;
import de.teamlapen.vampirism.util.Permissions;
import de.teamlapen.vampirism.util.REFERENCE;
import de.teamlapen.vampirism.world.MinionWorldData;
import de.teamlapen.vampirism.world.VampirismWorld;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.GenerationSettings;
import net.minecraft.world.gen.OverworldChunkGenerator;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;

/**
 * Handles all events used in central parts of the mod
 */
public class ModEventHandler {

    private final static Logger LOGGER = LogManager.getLogger(ModEventHandler.class);


    @SubscribeEvent(priority = EventPriority.LOW)
    public void on(WorldEvent.Load event) {
        IWorld w = event.getWorld();
        if (w instanceof ServerWorld) {
            ChunkGenerator<?> generator = ((ServerWorld) w).getChunkProvider().generator;
            if (generator instanceof OverworldChunkGenerator) {
                GenerationSettings settings = ((OverworldChunkGenerator) generator).getSettings();
                ModWorld.modifyVillageSize(settings);
            }
        }

    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        VersionChecker.VersionInfo versionInfo = VampirismMod.instance.getVersionInfo();
        if (!versionInfo.isChecked()) LOGGER.warn("Version check is not finished yet");

        boolean isAdminLikePlayer = !ServerLifecycleHooks.getCurrentServer().isDedicatedServer() || UtilLib.isPlayerOp(event.getPlayer());

        if (VampirismConfig.COMMON.versionCheck.get() && versionInfo.isNewVersionAvailable()) {
            if (isAdminLikePlayer || event.getPlayer().getRNG().nextInt(5) == 0) {
                if (event.getPlayer().getRNG().nextInt(4) == 0) {
                    VersionChecker.Version newVersion = versionInfo.getNewVersion();
                    event.getPlayer().sendMessage(new TranslationTextComponent("text.vampirism.outdated", versionInfo.getCurrentVersion().name, newVersion.name));
                    ITextComponent download = new TranslationTextComponent("text.vampirism.update_message.download").applyTextStyle(style -> style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, newVersion.getUrl() == null ? versionInfo.getHomePage() : newVersion.getUrl())).setUnderlined(true).setColor(TextFormatting.BLUE));
                    ITextComponent changelog = new TranslationTextComponent("text.vampirism.update_message.changelog").applyTextStyle(style -> style.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/vampirism changelog")).setUnderlined(true));
                    ITextComponent modpage = new TranslationTextComponent("text.vampirism.update_message.modpage").applyTextStyle(style -> style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, versionInfo.getHomePage())).setUnderlined(true).setColor(TextFormatting.BLUE));
                    event.getPlayer().sendMessage(download.appendText(" ").appendSibling(changelog).appendText(" ").appendSibling(modpage));
                }
            }
        }
        if (isAdminLikePlayer) {
            List<String> mods = IntegrationsNotifier.shouldNotifyAboutIntegrations();
            if (!mods.isEmpty()) {
                event.getPlayer().sendMessage(new TranslationTextComponent("text.vampirism.integrations_available.first"));
                event.getPlayer().sendMessage(new StringTextComponent(TextFormatting.BLUE + TextFormatting.ITALIC.toString() + org.apache.commons.lang3.StringUtils.join(mods, ", ") + TextFormatting.RESET));
                event.getPlayer().sendMessage(new TranslationTextComponent("text.vampirism.integrations_available.download").applyTextStyle(style -> style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, REFERENCE.INTEGRATIONS_LINK)).setUnderlined(true)));
            }

            if (!ModList.get().isLoaded("guideapi-vp")) {
                if (VampirismConfig.SERVER.infoAboutGuideAPI.get()) {
                    event.getPlayer().sendMessage(new TranslationTextComponent("text.vampirism.guideapi_available.first"));
                    event.getPlayer().sendMessage(new TranslationTextComponent("text.vampirism.guideapi_available.download").applyTextStyle(style -> style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, REFERENCE.GUIDEAPI_LINK)).setUnderlined(true)));

                    VampirismConfig.SERVER.infoAboutGuideAPI.set(false);
                }
            }
        }

        VampirismMod.dispatcher.sendTo(new SkillTreePacket(VampirismMod.proxy.getSkillTree(false).getCopy()), (ServerPlayerEntity) event.getPlayer());

        @SuppressWarnings("unchecked")
        Pair<Map<ResourceLocation, Integer>, Integer>[] bloodValues = (Pair<Map<ResourceLocation, Integer>, Integer>[]) Array.newInstance(Pair.class, 3);
        bloodValues[0] = new Pair<>(((VampirismEntityRegistry) VampirismAPI.entityRegistry()).getBloodValues(), ((VampirismEntityRegistry) VampirismAPI.entityRegistry()).getBloodMultiplier());
        bloodValues[1] = new Pair<>(BloodConversionRegistry.getItemValues(), BloodConversionRegistry.getItemMultiplier());
        bloodValues[2] = new Pair<>(BloodConversionRegistry.getFluidValues(), BloodConversionRegistry.getFluidDivider());

        VampirismMod.dispatcher.sendTo(new BloodValuePacket(bloodValues), (ServerPlayerEntity) event.getPlayer());
        FactionPlayerHandler.getOpt(event.getPlayer()).ifPresent(FactionPlayerHandler::onPlayerLoggedIn);

        if (!PermissionAPI.hasPermission(event.getPlayer(), Permissions.VAMPIRISM)) {
            event.getPlayer().sendMessage(new StringTextComponent("[" + TextFormatting.DARK_PURPLE + "Vampirism" + TextFormatting.RESET + "] It seems like the permission plugin used is not properly set up. Make sure all players have 'vampirism.*' for the mod to work (or at least '" + Permissions.VAMPIRISM + "' to suppress this warning)."));
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        VampirismAPI.getGarlicChunkHandler(event.getWorld().getWorld()).clear();
        TotemHelper.clearCacheForDimension(event.getWorld().getDimension());
    }

    @SubscribeEvent
    public void onAttachCapabilityWorld(AttachCapabilitiesEvent<World> event) {
        event.addCapability(REFERENCE.WORLD_CAP_KEY, VampirismWorld.createNewCapability(event.getObject()));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) return;
        MinionWorldData.getData(ServerLifecycleHooks.getCurrentServer()).tick();

    }
}
