package btw.modern;

/**
 * Dead type-only stub. Nothing links to btw.modern.FCEntityLightningBolt since the frozen
 * 1.5.2 artifact was regenerated (2026-10-10): the live bolt is
 * net.minecraft.src.btw.entity.FCEntityLightningBolt (extends btw.modern.EntityWeatherEffect),
 * which BTWEntityRegistration binds for fc_lightning_bolt. It stays on BOTH shadow lists in
 * Forge/build.gradle (jar exclude + removeModernCommonShadowedClasses), so it ships nowhere;
 * tools/LinkAudit reports it as an informational orphan. Follow-up: delete this file and
 * both list entries together (never un-list it alone: it would then ship extending the frozen
 * Entity without implementing its abstract NBT methods).
 */
public class FCEntityLightningBolt extends Entity {
    public FCEntityLightningBolt(World world) { super(world); }
    @Override public void entityInit() {}
}
