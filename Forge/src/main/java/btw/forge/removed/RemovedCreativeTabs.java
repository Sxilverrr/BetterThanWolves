package btw.forge.removed;

import btw.forge.BTWForgeMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides the T1 (stubbed) and T2 (retired) ids of {@link RemovedContent} from every creative tab, as 4.B did; T3
 * (recipe-only) ids stay listed, as in 4.B (removed_blocks.md section 3: FCItemStub / FCBlockStub / the inert vanilla
 * classes and the legacy-substituted blocks all had null tabs; wooden tools, the snow layer and the furnace kept
 * theirs).
 *
 * <p><b>Maintainer ruling:</b> "blocks that 4.B explicitly removed must stay removed." Hiding T1 and T2 follows from
 * the ruling directly and does not wait on the broader CT6 creative-tab question (music discs, enchanted books,
 * legacy wheat), which this class deliberately leaves alone.</p>
 *
 * <p>Runs at LOWEST priority on the MOD bus, after vanilla's pre-filled entries (ForgeHooks.java:1618-1638) and any
 * mod that adds to a tab, and removes the entries from the tab's {@code MutableHashedLinkedMap}
 * (BuildCreativeModeTabContentsEvent.java:72). The Search tab is the union of the tabs' search contents, and JEI
 * builds its item list from the same contents (jei ItemStackListFactory.java:55-84), so both follow. Creative
 * players can still {@code /give} every id, as in 4.B.</p>
 */
// FCMOD-INTEGRATION(1.20.1): stays-removed: 4.B listed no T1/T2 id in creative; the vanilla tabs (and JEI) follow
@Mod.EventBusSubscriber(modid = BTWForgeMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RemovedCreativeTabs {

    private static final Logger LOGGER = LogManager.getLogger("BTW-RemovedContent");

    /** Tabs already reported (tabs rebuild on permission and feature-flag changes; log once per tab). */
    private static final Set<ResourceKey<CreativeModeTab>> LOGGED = ConcurrentHashMap.newKeySet();

    private RemovedCreativeTabs() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hideRemoved(BuildCreativeModeTabContentsEvent event) {
        try {
            List<ItemStack> hidden = new ArrayList<>();
            for (Map.Entry<ItemStack, CreativeModeTab.TabVisibility> entry : event.getEntries()) {
                ItemStack stack = entry.getKey();
                if (stack != null && !stack.isEmpty() && RemovedContent.hideInCreative(stack.getItem())) {
                    hidden.add(stack);
                }
            }
            for (ItemStack stack : hidden) {
                event.getEntries().remove(stack);
            }
            if (!hidden.isEmpty() && LOGGED.add(event.getTabKey())) {
                List<String> names = new ArrayList<>();
                for (ItemStack stack : hidden) names.add(String.valueOf(stack.getItem()));
                LOGGER.info("[REMOVED] creative tab {}: hid {} removed entries {}", event.getTabKey().location(),
                        hidden.size(), names);
            }
        } catch (Throwable t) {
            LOGGER.warn("[REMOVED] could not hide removed ids from creative tab {}: {}", event.getTabKey(), t.toString());
        }
    }
}
