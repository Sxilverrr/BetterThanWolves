# FC ↔ Modern MC Integration Protocols

This document defines the contract for reconciling FC gameplay with
post-1.5.2 Minecraft features. Every integration lives here as a
numbered, audited entry. Nothing gets silently modified.

> **FC code is read-only. Never modify it.** Every integration lives in
> port code: the Forge bridge, Modern-Common, or the planned
> FC-extension subproject (Protocol #1).

---

## Protocols (strict)

1. **FC code is read-only. Never modify it.** (maintainer directive, 2026-10-10)
   "FC code" means FlowerChild's BTW sources (`net.minecraft.src.btw.*`
   under `Common/`, `Client/`, `Server/`), the 1.5.2 vanilla sources with
   FC's `FCMOD` edits under `vanilla/`, the frozen compiled 1.5.2/FC
   classes (root `build/classes/java/{main,server}`), FlowerChild's
   original 4.B release files kept in the repo (`Src/`, `SrcServer/` and
   `SrcClient/`, holding the flat sources and the `patch.txt` files that
   `setupVanilla` applies to build `vanilla/`, plus
   `BTWMod4-B0000003.zip`), and FlowerChild's own files such as
   `Readme.txt`. Nobody edits them, ever: no "small fixes", no tagged
   edits. All adaptation happens outside FC code:
   - the **Forge bridge** (`Forge/src/main/java/btw/forge`, mixins on
     1.20.1 classes, data packs);
   - **Modern-Common** (`btw.modern`), the port's own pure-Java stand-ins
     for 1.5.2 vanilla classes. This is port code, not FC code;
   - a **planned FC-extension subproject (name TBD)** that *extends* FC
     code (subclasses, registries, event hooks, replacement classes) and
     *patches* it (bytecode transforms or mixins applied at build or load
     time). Patches never write back to FC sources. The build-time ASM
     transforms in `Forge/build.gradle` (`widenFcHookDescriptors`,
     `openUpEntityAccess`, `extendFcPathingBuildRange`) are its
     precursors and are expected to move into it.

   If FC behaviour must change, it changes through that extension/patch
   layer, never by editing FC files. Older port edits to FC code that
   predate this rule are listed in `BRIDGE_SPEC.md` (Key Principles).
   Nothing is added to them.

   **Tag port code, not FC code.** Integration code in the bridge or the
   subproject carries a tag so it stays auditable:
   Java: `// FCMOD-INTEGRATION(1.20.1): <feature>: <one-line why>`
   Mixins: full Javadoc block on the class (Conflict / Pattern / Rebalance / Rejected alternatives).
   The tag name is legacy. Its `FCMOD` prefix echoes FC's own markers in
   `vanilla/`, but `FCMOD-INTEGRATION` marks port code only, never FC
   code. Existing tags keep the name; there is no code rename.

2. **Gate modern affordances through FC infrastructure — don't delete them.**
   If vanilla gives you X for free, attach an FC cost to the channel,
   don't remove the channel.

3. **Prefer data-driven overrides over code changes** (loot tables,
   recipes, tags, gamerules, biome modifiers). Code changes only when
   data can't express the integration.

4. **Prefer Forge events over mixins** when you only need to observe/cancel.
   Mixin only for surgical bytecode changes where no event exists.

5. **Keep both items/blocks dual-sourced.** Never silently replace vanilla X
   with FC Y — let vanilla X still exist and rebalance it so FC's version
   is the natural choice. Preserves compat with other mods.

6. **Version-lock the assumption.** Every integration names `(1.20.1)` in
   its tag. A future port knows exactly what to re-examine.

7. **Test with "what if another mod depends on this"** before shipping.

8. **Integrate, don't disable.** (maintainer stance, 2026-10-10)
   In the maintainer's words: "What I don't want to do is the lazy thing,
   which is just disable stuff; you'd might as well load up BTW on 1.5.2
   if the 'modern port' just gets rid of all the modern features."
   Integrate BTW's features with modern Minecraft in fun and interesting
   ways. Disabling, capping or removing a modern feature is a last
   resort, and the entry must justify it. Every entry must say what BTW
   **adds** to the feature, not only how it is limited. See
   [Integration directions](#integration-directions-2026-10-10).

---

## Integration patterns

Choose one per feature. Integrations that combine patterns must justify
the combination.

| Pattern | Name | When to use |
|---------|------|-------------|
| **A** | Tier-align | Modern item is fine in concept, just belongs at FC's equivalent tier. |
| **B** | Gate | Require FC infrastructure / material to unlock or use. |
| **C** | Rebalance | Keep the feature; adjust numbers so it slots into FC's economy. |
| **D** | FC-extend | BTW variants built on the modern feature's framework, in the extension layer (FC code stays read-only). |
| **E** | Naturalize | Leave vanilla intact, buff FC's version so players prefer FC. |

Under Protocol #8, prefer **D** and **B** (gated by BTW progression). A
**C** entry that only subtracts needs a written justification.

---

## Integration log

### #001 — Mending enchantment

| Field | Value |
|-------|-------|
| **Modern feature** | Mending enchantment (MC 1.9+) |
| **Added** | 2016-02 / MC 1.9 |
| **Conflict** | Infinite durability trivialises FC's anvil / steel / soulforge repair loop |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`MendingCapMixin`](Forge/src/main/java/btw/forge/mixin/MendingCapMixin.java) |
| **Effect** | Each mending XP orb restores **½** the vanilla durability amount. XP cost is unchanged. |
| **Dual-sourced?** | Yes — the enchantment still appears in loot, trades, and enchanting table RNG. |
| **Data or code?** | Code (mixin). Data-driven override was not possible — Mending's repair amount is hard-coded in `ExperienceOrb.repairPlayerItems`. |
| **Alt rejected** | Gate mending-books behind soulforge. Rejected under Protocol #5 (would remove mending from vanilla enchanting RNG, breaks other mods). |
| **Verified by** | Refmap emits `m_147092_` (repairPlayerItems) + `m_41721_` (setDamageValue) mappings → production build applies correctly. |
| **Net effect on FC loop** | A mending pickaxe now takes 2× as many XP orbs to fully repair, so the anvil / soulforge chain is still the fastest maintenance route for heavy use. Mending remains a quality-of-life boon, not a tool-immortality switch. |
| **Under review (2026-10-10)** | Subtractive (repair halved). Direction: make Mending an earned enchantment through BTW's arcane-scroll / Infernal Enchanter framework (source mob to decide) instead of a weakened one. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #002 — Gamerule defaults

| Field | Value |
|-------|-------|
| **Modern feature** | Post-1.5.2 gamerules: `doInsomnia` (1.13), `doPatrolSpawning` (1.14), `doTraderSpawning` (1.14), `doWardenSpawning` (1.19), `keepInventory` (1.4) |
| **Added** | MC 1.4 – 1.19 |
| **Conflict** | Phantoms double-punish FC's sleep cost; pillager patrols and wandering traders drop loot / sell items that shortcut FC progression; warden is irrelevant to FC design; keepInventory removes FC's load-bearing death penalty |
| **Pattern** | **C** (rebalance via data — set FC-appropriate defaults, preserve user override) |
| **Implementation** | [`WorldGamerulesHandler`](Forge/src/main/java/btw/forge/WorldGamerulesHandler.java) |
| **Effect** | New worlds get `doInsomnia=false`, `doPatrolSpawning=false`, `doTraderSpawning=false`, `doWardenSpawning=false`, `keepInventory=false`. Existing worlds are untouched. Users can override via `/gamerule`. |
| **Dual-sourced?** | Yes — gamerules are vanilla-native and fully reversible |
| **Data or code?** | Event handler (Forge `LevelEvent.CreateSpawnPosition`). No mixin, no bytecode. |
| **Alt rejected** | `ServerAboutToStartEvent` (fires every server start — would override user preferences on restart, violates Protocol #2). |
| **Verified by** | New world → `/gamerule doInsomnia` reports `false`. Existing world → user-set values unchanged. |
| **Net effect on FC loop** | Removes phantom/patrol/trader/warden distractions from FC's early game. Death penalty stays intact by default. |
| **Under review (2026-10-10)** | Subtractive (four features switched off). Direction: replace the off-switches with BTW progression gates (FC already tracks Nether, Wither and End progress); phantoms hunt in the dark, tied to BTW gloom; the warden guards the richest soul source. `keepInventory=false` is not subtractive and stays. See [Integration directions](#integration-directions-2026-10-10), first move 2. |

### #003 — Pattern-E batch (naturalized, no code)

| Field | Value |
|-------|-------|
| **Modern feature** | 21 post-1.5.2 additive features with no FC-design friction. |
| **Added** | MC 1.7 – 1.20 |
| **Conflict** | None that degrade the FC core loop. Each row is either purely cosmetic, a compatible mob variant, or a niche effect FC already tolerates. |
| **Pattern** | **E** (naturalize — allow feature, FC doesn't compete) |
| **Implementation** | None. Features ship vanilla-intact. |
| **Effect** | These rows are reviewed and batch-approved for unchanged behavior. They remain fully present in the game with vanilla mechanics. |
| **Dual-sourced?** | Yes — all items/mechanics remain vanilla-reversible via datapack removal. |
| **Data or code?** | Neither. Documentation entry only. |
| **Alt rejected** | Writing one integration entry per cosmetic row. Rejected: inflates the log with 21 rows that document "no change", drowning real integrations; batching preserves auditability (each row is listed below) while keeping the log scannable. |
| **Verified by** | Static review against Protocol #5 (don't silently replace vanilla). No runtime test — Pattern-E rows are definitionally no-op. |
| **Net effect on FC loop** | Zero. |
| **Batched rows** | Boss bars (1.10); Pufferfish poison teach (1.7); Curse of Vanishing (1.11); Curse of Binding (1.11); Loom (1.14); Cartography table (1.14); Fletching table (1.14); Polar bear (1.10); Husk/stray/drowned (1.10/1.13); Frog/tadpole (1.19); Cat (1.14); Fox (1.14); Panda (1.14); Glowing effect (1.9); Luck / Bad Luck effects (1.9); Levitation effect (1.9); Music discs (1.10+); Banner patterns (1.8); Armor trims (1.20); Hanging signs (1.20); Decorated pots (1.20). |
| **Under review (2026-10-10)** | Contradiction: Wave 2 (W2)'s fix for triage SW5 replaces drowned, husks and strays (plus glow squid) with base FC mobs, which contradicts the *Husk/stray/drowned* row above. Wither skeletons and existing zombie villagers keep their 1.5.2 identities (FC's type-1 wither skeleton and FC's villager zombie); only zombie villagers from new natural, worldgen or spawner spawns become plain FC zombies, matching BTW 4.B, which never spawned villager zombies. Direction: rework the collapse into FC variants that keep the modern identities. Other rows here now have integration directions: Fletching table (BTW arrow bench), Decorated pots (turntable + kiln pottery chain), Cat (scares FC creepers). See [Integration directions](#integration-directions-2026-10-10), Mobs, Workstations and Materials. |

### #004 — Loot-table nerfs (data-driven)

| Field | Value |
|-------|-------|
| **Modern feature** | Post-1.9 loot tables that drop progression-skipping items — bastion chests, ancient city chests, pillager outpost chests, woodland mansion chests, trail-ruins archaeology |
| **Added** | MC 1.9 – 1.20 |
| **Conflict** | Free high-tier loot (ancient debris, diamond gear, enchanted books, iron pickaxes, music discs) bypasses FC's mining/smithing/enchant chains. Players who find a bastion can skip entire tiers of progression |
| **Pattern** | **C** (rebalance via data — keep feature, subtract the progression-skipping items) |
| **Implementation** | Global Loot Modifiers registered via [`BTWLootModifiers`](Forge/src/main/java/btw/forge/data/BTWLootModifiers.java) + [`FCRemoveItemFromLootModifier`](Forge/src/main/java/btw/forge/data/FCRemoveItemFromLootModifier.java). JSON instances emitted by [`FCLootOverrides`](Forge/src/main/java/btw/forge/data/FCLootOverrides.java) during `runData` |
| **Effect** | The following items are stripped from their generating loot tables: ancient debris (all 4 bastion chest tiers); diamond leggings + diamond horse armor (ancient city); iron pickaxe + enchanted books (pillager outpost); diamond chestplate (woodland mansion); Relic music disc (trail-ruins brushing). **10 Global Loot Modifiers total.** |
| **Dual-sourced?** | Yes — vanilla loot tables are untouched; GLMs are an additive layer that server ops can disable via pack priority |
| **Data or code?** | Both — one custom `FCRemoveItemFromLootModifier` codec class (~60 lines) + JSON GLM instances generated via data-gen |
| **Alt rejected** | Full loot-table replacement JSON. Rejected because (a) brittle — vanilla loot tables change between MC patches, we'd have to re-sync; (b) breaks compat with other mods that also modify those tables via GLMs; (c) more JSON per table |
| **Verified by** | `gradlew :Forge:runData` emits 10 JSON files under `src/generated/resources/data/betterthanwolves/loot_modifiers/` plus `global_loot_modifiers.json` index. In-world: open a bastion treasure chest, confirm ancient debris absent from drops |
| **Net effect on FC loop** | Structure exploration stays valuable (players still find emeralds, books, food, situational items), but no structure is a "skip to tier N" shortcut. Netherite progression requires netherite-tier mining (still possible; just not chest loot) |
| **Under review (2026-10-10)** | Subtractive (items stripped, nothing added). Direction: structures and archaeology carry BTW lore items (arcane scrolls, rare seeds) so exploration feeds BTW progression. See [Integration directions](#integration-directions-2026-10-10), Materials. |

### #005 — Stonecutter slab-output rebalance (data-driven)

| Field | Value |
|-------|-------|
| **Modern feature** | Stonecutter (MC 1.14) as a 2× efficiency multiplier over crafting table for slab recipes |
| **Added** | MC 1.14 |
| **Conflict** | Stonecutter turns 1 stone → 2 stone slabs, while the crafting table gives 6 slabs from 3 stone (2 per stone). Net: stonecutter matches crafting-table efficiency *and* lets the player skip all shape-swap intermediate crafts. In FC's economy, where stone is a foundational tier the player earns through mining with a wood/stone pickaxe, the shortcut erodes the early-game mining loop |
| **Pattern** | **C** (rebalance via data — halve stonecutter slab output to 1, matching its output pattern for stairs/walls/etc.) |
| **Implementation** | [`FCRecipeOverrides`](Forge/src/main/java/btw/forge/data/FCRecipeOverrides.java) — 30 stonecutter recipes regenerated at their vanilla resource locations with `count=1` |
| **Effect** | Stonecutter-produced slabs across stone, cobblestone, mossy, smooth, bricks, granite, diorite, andesite, sandstone (regular/smooth/cut/red), prismarine family, nether bricks, end stone bricks, blackstone family, deepslate family, and mud bricks: output count halved from 2 → 1 |
| **Dual-sourced?** | Yes — players can still craft slabs via the 3-stone → 6-slab crafting-table recipe (vanilla, untouched). Stonecutter remains functional, just no longer a strict improvement |
| **Data or code?** | Pure data (generated JSON at `data/minecraft/recipes/*_stonecutting.json` override paths) |
| **Alt rejected** | Remove stonecutter slab recipes entirely. Rejected under Protocol #5 — removes a vanilla affordance rather than rebalancing it; would also break compat with mods that expect stonecutter-to-slab paths |
| **Verified by** | `gradlew :Forge:runData` emits 30 JSON files under `src/generated/resources/data/minecraft/recipes/` (committed; the Forge jar task fails if the generated dir is missing). In-world: load recipe book → stonecutter with 1 stone shows `1 stone_slab` output (was `2`) |
| **Net effect on FC loop** | Early-game stone economy retains its weight. Stonecutter is still the "right tool" for non-slab shapes (stairs, walls, cut variants) since those were already 1:1 |
| **Under review (2026-10-10)** | Subtractive (output halved). Direction: the stonecutter as a mechanically powered stone saw that outputs BTW shapes. See [Integration directions](#integration-directions-2026-10-10), Workstations. |

### #006 — Frost Walker radius cap

| Field | Value |
|-------|-------|
| **Modern feature** | Frost Walker enchantment (MC 1.9+) — freezes water blocks in a radius around the wearer while walking |
| **Added** | MC 1.9 |
| **Conflict** | Vanilla level-1 Frost Walker freezes a 5×5 area (radius 2) under the player, turning any body of water into walkable terrain. FC worldgen tunes water crossings as deliberate traversal obstacles (swim exhaustion, water slowdown, limited ice-breaking). Free water crossings defeat that design |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`FrostWalkerRadiusMixin`](Forge/src/main/java/btw/forge/mixin/FrostWalkerRadiusMixin.java) — `@ModifyConstant` on the `2` literal inside `onEntityMoved`, reducing base radius from `2 + level` to `0 + level` |
| **Effect** | Level 1 → 1×1 freeze (block under player only); Level 2 → 3×3 freeze (small puddle). Enchantment still rolls in loot and enchanting table |
| **Dual-sourced?** | Yes — enchantment remains in vanilla loot pools and enchanting RNG |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove Frost Walker from treasure-enchant pool. Rejected under Protocol #5 — removes rather than rebalances |
| **Verified by** | Refmap at `Forge/build/tmp/compileJava/betterthanwolves.refmap.json` emits `m_45018_` mapping for `onEntityMoved` → production build applies correctly |
| **Net effect on FC loop** | Water crossings remain deliberate traversal choices. Frost Walker is now a modest slip-protection perk rather than a free-bridge enchant |
| **Under review (2026-10-10)** | Subtractive (radius capped). Direction: Frost Walker comes from a stray arcane scroll, so it is a rare, earned top-tier enchantment rather than a nerfed one. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #007 — Respawn anchor single-use

| Field | Value |
|-------|-------|
| **Modern feature** | Respawn Anchor (MC 1.16+) — Nether block that stores up to 4 glowstone charges, each allowing one respawn at the anchor |
| **Added** | MC 1.16 |
| **Conflict** | FC's HC Spawn (integration `#002`) makes initial respawn location load-bearing for early-game progression. The respawn anchor is a portable Nether-side second spawn, stacking 4 deep — removes death consequence entirely for long Nether stretches |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`RespawnAnchorChargeMixin`](Forge/src/main/java/btw/forge/mixin/RespawnAnchorChargeMixin.java) — `@ModifyConstant` on the `4` literal inside `canBeCharged(BlockState)`, capping at 1 |
| **Effect** | Respawn anchor accepts exactly one charge. Using it to respawn consumes the charge and requires re-charging. Per `docs/audit/alchemy.md`, each charge costs `fcItemConcentratedHellfire` (gating follow-up, deferred) |
| **Dual-sourced?** | Yes — anchor block and recipe remain vanilla-reachable |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Delete respawn anchor recipe. Rejected under Protocol #5 — breaks player autonomy; also leaves dangling block in world if players find one in structure loot |
| **Verified by** | Refmap emits `m_55894_` mapping for `canBeCharged` → production build applies correctly |
| **Net effect on FC loop** | Anchor becomes a "one escape route" commitment rather than a routine safety net. HC Spawn stays meaningful |
| **Under review (2026-10-10)** | Subtractive (charges capped at 1). Direction: the anchor becomes the Nether's fixed spawn, charged with soul material instead of glowstone. See [Integration directions](#integration-directions-2026-10-10), Death and hardcore spawn. |

### #008 — Swift Sneak level cap

| Field | Value |
|-------|-------|
| **Modern feature** | Swift Sneak enchantment (MC 1.19+) — legging-slot enchant that reduces the sneak-speed penalty; at level 3 sneak speed approaches full walking speed |
| **Added** | MC 1.19 |
| **Conflict** | FC's cave mining loop treats crouch as a deliberate speed trade-off for ledge safety. Swift Sneak III eliminates the trade entirely, making crouched mining strictly better than upright — collapses a moment-to-moment design decision FC expects the player to make |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`SwiftSneakLevelMixin`](Forge/src/main/java/btw/forge/mixin/SwiftSneakLevelMixin.java) — `@Inject(HEAD, cancellable)` on `getMaxLevel`, forces return value to `1` |
| **Effect** | Swift Sneak rolls only at level 1 via enchanting table and Forge enchantment RNG. Level 1 still provides a partial mitigation (useful for long mining) without eliminating the trade-off |
| **Dual-sourced?** | Yes — enchant remains available via all vanilla pathways |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove Swift Sneak from ancient-city loot. Rejected under Protocol #5 |
| **Known limitation** | Pre-generated ancient-city loot rolling Swift Sneak III books still functions at level 3 (level is NBT-stored and consulted at consumption). A consumption-time clamp via `EnchantmentHelper#getEnchantmentLevel` remains possible if playtest shows it matters |
| **Verified by** | Refmap emits `m_6586_` mapping for `getMaxLevel` → production build applies correctly |
| **Net effect on FC loop** | Crouch-vs-walk is a meaningful decision in FC mining again. Swift Sneak becomes "modest comfort boost" rather than "crouch-mode upgrade path" |
| **Under review (2026-10-10)** | Subtractive (level capped). Direction: Swift Sneak comes from a warden arcane scroll, a rare, earned top-tier enchantment. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #009 — Totem of Undying soul-flux gate

| Field | Value |
|-------|-------|
| **Modern feature** | Totem of Undying (MC 1.11+) — held totem activates on lethal damage, restoring 1 HP + Regen + Fire Resist + Absorption, consuming the totem |
| **Added** | MC 1.11 |
| **Conflict** | Free death-save short-circuits FC's HC Spawn penalty (integration `#002`). Evokers drop totems at 100% and can be farmed, which turns late-game FC into "accumulate totems, become immortal" |
| **Pattern** | **B** (gate via FC reagent — preserve feature, route through FC's alchemy chain) |
| **Implementation** | [`TotemOfUndyingMixin`](Forge/src/main/java/btw/forge/mixin/TotemOfUndyingMixin.java) — `@Inject(HEAD, cancellable)` on `LivingEntity#checkTotemDeathProtection(DamageSource)` |
| **Effect** | Totem still required in a hand (vanilla behavior), but also requires at least one `fcItemSoulFlux` (item ID 2555) in the player's inventory. On save, one soul flux is consumed alongside the totem. Without soul flux, totem doesn't save. Non-player entities unaffected. Damage sources that bypass invulnerability are passed through to vanilla unchanged (no soul flux consumed on unsavable deaths) |
| **Dual-sourced?** | Yes — totems remain in vanilla loot (evoker drops, woodland-mansion chests), and the vanilla activation mechanic is untouched when soul flux is present |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Remove totem from evoker drops. Rejected under Protocol #5 — loot-source removal breaks other mods and is heavier than an activation cost |
| **Alt rejected (2)** | `@Redirect` on `shrink(1)` inside vanilla's totem block. Rejected because if we skip the shrink, vanilla still returns `true` and applies save effects, causing the totem to save without being consumed — bug in the wrong direction |
| **Verified by** | Refmap emits `m_21262_` for `checkTotemDeathProtection`. In-game test: lethal damage with totem but no soul flux → player dies; with totem + soul flux → player saved, totem consumed, soul flux decremented |
| **Net effect on FC loop** | Totem saves become endgame commitments rather than routine escape hatches. HC Spawn retains its weight through late game |
| **Model entry (2026-10-10)** | The one true FC-infrastructure gate in the log: the feature stays whole and BTW's soul/alchemy chain gives it a cost. Later entries should follow this shape (Protocol #8). |

### #010 — Crossbow piercing cap

| Field | Value |
|-------|-------|
| **Modern feature** | Crossbow PIERCING enchantment (MC 1.14+) — each level allows projectiles to penetrate one additional entity |
| **Added** | MC 1.14 |
| **Conflict** | FC's ranged-combat armor-bypass mechanism is the `FCItemArrowBroadhead` item (designed-in). Piercing III+ fires through walls of mobs, turning FC spider swarms and zombie hordes into one-shot cleanups, collapsing FC's number-based encounter tuning |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`CrossbowPiercingMixin`](Forge/src/main/java/btw/forge/mixin/CrossbowPiercingMixin.java) — `@Redirect` on `AbstractArrow#setPierceLevel(byte)` inside `CrossbowItem#shootProjectile` |
| **Effect** | When a crossbow projectile is fired, the piercing level applied to the outgoing arrow is clamped to `min(1, enchantLevel)`. Enchantment still appears in loot and on NBT at any level; only the fire-time effect is capped |
| **Dual-sourced?** | Yes — Piercing stays in enchant pools; crossbows with level-4 piercing still exist in loot |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | Overwrite `PiercingEnchantment.getMaxLevel()` to 1. Rejected because pre-generated loot already has level-3/4 crossbows; level is stored in NBT and consulted at fire time regardless of max-level API. Fire-time clamp is more robust |
| **Verified by** | Refmap emits `m_40894_` for `shootProjectile` (10-arg method matching CrossbowItem's static shoot). In-game: Piercing V crossbow fires → hit first zombie in a line, second zombie unaffected |
| **Net effect on FC loop** | Crossbow remains viable ranged weapon, but mobs-in-a-line no longer cascade-die. Spider/zombie swarms retain their intended threat |
| **Under review (2026-10-10)** | Subtractive (fire-time cap). Direction: Piercing, Multishot and Quick Charge come from pillager arcane scrolls, rare and earned. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### #011 — Sweeping Edge suppression

| Field | Value |
|-------|-------|
| **Modern feature** | Sweeping Edge enchantment (MC 1.11+) — sword enchant that inflicts reduced AoE damage on entities near the primary target |
| **Added** | MC 1.11 |
| **Conflict** | FC combat is per-target-hit — each swing costs exhaustion, and damage is reconciled individually. AoE melee breaks that accounting: a single swing can kill multiple mobs without paying the per-mob exhaustion cost. FC's spider swarms and husk clusters are tuned as numerically-threatening encounters, not as loot-bundles |
| **Pattern** | **C** (rebalance, preserve feature) |
| **Implementation** | [`SweepingEdgeMixin`](Forge/src/main/java/btw/forge/mixin/SweepingEdgeMixin.java) — `@Redirect` on `Level#getEntitiesOfClass(Class, AABB)` inside `Player#attack`, returning empty list |
| **Effect** | Sweeping animation, sound, and particle still fire — visible feedback preserved. But the peripheral-entity list used for sweep damage is empty, so no secondary entity takes damage. Sweeping Edge is effectively cosmetic |
| **Dual-sourced?** | Yes — enchant still rolls at all levels, still applies to swords, still displays in item tooltip |
| **Data or code?** | Code (mixin) |
| **Alt rejected** | `@Redirect` on `EnchantmentHelper.getSweepingDamageRatio` returning 0. Rejected because vanilla formula is `sweepMul = 1.0f + ratio × damage`; ratio=0 still yields sweepMul=1.0, so peripheral entities take 1 damage per sweep. Zero-target is cleaner |
| **Alt rejected (2)** | Overwrite `Player#attack`'s sweep block. Rejected — high mixin-conflict risk with mods injecting into attack for stat/damage reasons |
| **Verified by** | Refmap emits `m_5706_` for `attack` + `m_45976_` for `getEntitiesOfClass`. In-game: swing soulforged-steel sword at a sheep inside a 3-sheep clump → only the directly-targeted sheep takes damage |
| **Net effect on FC loop** | Per-target exhaustion cost is enforced. Crowd control via AoE is no longer a free bypass of FC's swing economy |
| **Under review (2026-10-10)** | Subtractive (enchantment made cosmetic). Direction: move Sweeping Edge onto an arcane scroll (source mob to decide) so a working sweep is a rare, earned top-tier enchantment. See [Integration directions](#integration-directions-2026-10-10), Enchanting. |

### Follow-up from #005 implementation

The stonecutter pass reinforced that several planned recipe overrides —
lodestone (needs FC soulforged steel), recovery compass (needs FC
arcane scroll), conduit disable (needs FC crucible replacement in the
same sprint to avoid regression) — are **blocked on stable resource
locations for FC items**. FC's legacy ParseID→index mapping renders
each item as `betterthanwolves:item_<runtime_index>`, which shifts if
the FC config changes ParseIDs. Promoted as a blocker to the deferred
`#006` tag-remediation entry.

---

## Integration directions (2026-10-10)

Recorded from a design discussion on 2026-10-10.
Nothing here is implemented. It applies Protocols #1 and #8. Points marked
*verify* come from memory of BTW 4.B, not from the repo: check them
against the FC source before building on them.

### Critique of the current log

- Protocol #2 says gate, don't delete, but most implemented entries
  subtract: #001 Mending halved, #006 Frost Walker capped, #008 Swift
  Sneak capped, #010 Piercing capped, #011 Sweeping Edge suppressed,
  #007 respawn anchor single-use, #005 stonecutter slabs halved, #004
  loot stripped, #002 phantoms, patrols, traders and wardens switched
  off by gamerule.
- The deferred tables read the same way: "disable or nerf", "limit
  count", "goat horn locked", "reduce range".
- They ask "how do we stop X trivialising BTW?" instead of "what does
  BTW make X mean?".
- Pattern D (FC-extend) is barely used. #009 (the totem costs soul flux)
  is the one true FC-infrastructure gate.
- Wave 2 (W2) of the 2026-10-10 fix effort (triage SW5) turned drowned,
  husks and strays into plain FC zombies and skeletons, and glow squid
  into FC squid. Wither skeletons map to FC's own type-1 wither
  skeleton, and existing zombie villagers stay villager zombies. It
  closed real gaps (these mobs ran 1.20.1 AI with no FC behaviour, and
  they triggered D2's per-tick scroll drops), but it deleted variety and
  contradicts #003, which naturalizes husk/stray/drowned.

### Principles

1. **Every modern material enters a BTW processing chain**: saw,
   millstone, turntable, kiln, crucible, cauldron, filtered hopper.
2. **Gate by BTW world progression, not by gamerules.** FC already
   tracks progress (`FCUtilsWorld.GameProgressHasNetherBeenAccessedServerOnly`,
   `...HasWitherBeenSummonedServerOnly`,
   `...HasEndDimensionBeenAccessedServerOnly`). Lighting a Nether portal
   sets the first flag (`FCBlockPortal`), and lit overworld portals
   possess nearby animals and villagers. `FCUtilsHardcoreSpawn` widens
   the random-respawn radius at each stage (×1.5 Nether, ×2 Wither, ×4
   End) and stops the shared early-game respawn once the Nether flag is
   set. That is the existing precedent for progression gating.
3. **Keep modern mob identities** and give them BTW behaviour, instead
   of collapsing them into base FC mobs.
4. **Give purposeless modern blocks BTW jobs.**

### Ideas by theme

**Death and hardcore spawn**
- BTW's random respawn (`FCUtilsHardcoreSpawn`) makes the recovery
  compass (echo shards, ancient cities), lodestones and maps genuinely
  valuable. Integrate them; don't nerf them.
- Respawn anchor as the Nether's fixed spawn, charged with soul
  material instead of glowstone (replaces #007's cap).
- Phantoms as things that hunt in the dark, tied to BTW gloom
  (`FCDamageSourceCustom.m_DamageSourceGloom`), instead of
  `doInsomnia=false` (#002).

**Souls (BTW's core theme)**
- Sculk is Mojang's own soul/death mechanic: catalysts bloom where mobs
  die. BTW captures souls with soul-sand-filtered hoppers
  (`FCTileEntityHopper`) and urns.
- Sculk as a soul source for urns; catalysts competing with soul
  hoppers; the warden guarding the richest soul source instead of
  `doWardenSpawning=false`. Soul-capture rules: *verify*.
- Soul fire as permanently "stoked" heat under the cauldron and
  crucible: a mid-game convenience earned in soul sand valleys
  (*verify* how stoked fire, `FCBlockFireStoked`, is made and decays).

**Mechanical power and redstone**
- BTW machines emit vibration game events: sculk sensors detect running
  mills, noisy industry in the deep dark summons the warden, and wool
  dampens it (a new job for BTW wool).
- Comparator outputs for FC machines: cauldron cook progress, hopper
  fill, urn souls.
- Animal power: a horse, donkey, camel or goat on a lead turns a BTW
  axle, a tier between the hand crank and the windmill. The goat's ram
  fits FC's existing `OnKickedByCow` hook (`FCEntityCow`,
  `FCEntityCreeper`).
- BTW buoyancy (items float or sink by type, `Item.GetBuoyancy`) plus
  bubble columns gives item elevators and buoyancy sorting, instead of
  "rebalance flow". Exact buoyancy rules: *verify*.
- BTW already has a lightning rod (`FCBlockLightningRod`). Copper rods
  feed the same lightning mechanics; oxidation weakens them and
  honeycomb wax (bees) preserves them.

**Materials**
- Deepslate, tuff and calcite as BTW's deeper stone strata. The seed
  exists: since 2026-10-10 `ModernBlockStandIn` reads deepslate as an
  anonymous solid stand-in (a legacy id allocated at runtime from
  159-174; 160 in the 2026-10-10 dev log); real FC stone behaviour grows
  from there.
- Copper melted in the crucible as an early metal.
- Mud bricks sun-dried like BTW's unfired bricks
  (`FCTileEntityUnfiredBrick` cooks in place, with rain decay).
- Decorated pots shaped on the turntable with sherds and kiln-fired,
  joining BTW's vase/urn pottery chain.
- Archaeology loot carrying BTW lore items (arcane scrolls, rare seeds).
- Cherry, mangrove, bamboo, crimson and warped wood through the saw:
  sidings, mouldings, corners, and bark for tanning.

**Workstations**
- Fletching table = BTW arrow bench (broadheads, composite bow).
- Stonecutter = mechanically powered stone saw that outputs BTW shapes.
- Blast furnace needs bellows or stoked heat.
- Composter feeds BTW's dung/fertilizer chain (*verify* the 4.B chain).
- Chiseled bookshelf powers the Infernal Enchanter by the books it
  holds (today `FCBlockInfernalEnchanter` counts `Block.bookShelf`
  within 8 blocks).

**Enchanting (the biggest win)**
- BTW's arcane scrolls (mob drops, used in the Infernal Enchanter)
  frame every post-1.5.2 enchantment:
  - Frost Walker from strays;
  - Soul Speed from piglins and wither skeletons;
  - Swift Sneak from wardens;
  - Riptide, Loyalty and Channeling from drowned;
  - Multishot, Piercing and Quick Charge from pillagers.
- The caps in #006, #008, #010 and #011 then become rare, earned
  top-tier enchantments instead of nerfs.

**Mobs**
- Modern identities as FC variants: drowned keep tridents; husks'
  hunger feeds BTW hunger penalties; strays keep slowness arrows; glow
  squid as BTW's night head-crab squid (`FCEntitySquid`).
- Modern animals under BTW husbandry (hunger, loose food, breeding
  harness):
  - bees pollinate hemp;
  - sniffers dig up hemp seeds (BTW drops them from grass,
    `FCBlockGrass`);
  - axolotls hunt FC squid;
  - a turtle helmet blocks squid head-crabbing;
  - cats scare FC creepers (FC creeper AI must learn that cats count
    like ocelots).
- Piglins barter BTW Nether goods; BTW nether growth spreads onto
  nylium.

**Teaching** (the "wiki-dependent" CurseForge complaint, triage CTX-3)
- An advancement tree that mirrors BTW's tech ladder, with recipe-book
  unlocks driven by it.

**Ecosystem** (brief)
- Tags (for example Forge ingot tags for soulforged steel), JSON loot
  and recipes, `IItemHandler` capabilities on BTW baskets and hoppers.
  Likely blocked on the same stable FC resource locations as the
  recipe overrides (see Follow-up from #005).

### Extension points the read-only rule requires

FC code cannot change, so integrations need these hooks in the bridge or
the planned FC-extension subproject:

| # | Extension point | Enables |
|---|-----------------|---------|
| a | Registry mapping modern blocks to FC **behaviour** (generalises `ModernBlockStandIn`) | Deepslate strata, chiseled bookshelves, copper rods, soul fire |
| b | AI injection so FC mobs recognise modern entities | Cats vs creepers, axolotls vs squid, goats on `OnKickedByCow` |
| c | Machine game events + comparator outputs | Sculk sensing, warden summoning, redstone readouts |
| d | Progression-flag API over FC's `GameProgress*` flags | Replacing the #002 gamerules with progression gates |

### Fidelity layering

- The faithful core stays 1.5.2-exact. Integrations are a separate,
  labelled layer on top.
- A purist server option (core only) is reasonable, but it must not be
  the default.

### Testing

- Forge's GameTest framework is loaded with no tests: the dev logs show
  `Enabled Gametest Namespaces: []`. Use it for in-world BTW regression
  tests of integrations and the faithful core.

### Suggested first moves

1. Adopt "integrate, don't disable" as a protocol (done: Protocol #8).
2. Turn the #002 gamerule switches into progression gates.
3. Move the modern enchantments onto arcane scrolls.
4. Rework the SW5 variant collapse into FC variants.

---

## Adding a new integration — checklist

When you pilot a new feature:

- [ ] Never edit FC code (Protocol #1). Put the change in the Forge
      bridge, Modern-Common or the FC-extension subproject.
- [ ] Say what BTW adds to the feature (Protocol #8). If the entry
      disables, caps or removes anything, justify why integration was
      not possible.
- [ ] Pick an integration pattern (A–E) and justify it in one sentence.
- [ ] Write an alternative you considered and rejected (forces you to
      explore the design space).
- [ ] Prefer Forge event → loot-table override → recipe override →
      mixin, in that order.
- [ ] If mixin, include a full Javadoc block on the class with:
      Feature / Conflict / Pattern / Rebalance / Injection strategy /
      Alt rejected.
- [ ] Tag the new port code (bridge or subproject, never FC code) with
      `// FCMOD-INTEGRATION(1.20.1): <feature>`.
- [ ] Add a new row to the Integration log above.
- [ ] Rebuild and verify refmap (`grep <MixinName>
      Forge/build/tmp/compileJava/betterthanwolves.refmap.json`).
- [ ] Smoke-test in dev run.
- [ ] Smoke-test in built jar (reobfuscation catches things dev misses).

---

## Tracking deferred integrations

Catalogue of post-1.5.2 MC features that touch FC's design surface and are
not yet integrated. Grouped by domain. Each row carries a tentative
pattern (A–E). Items already implemented live in the integration log
above and do **not** appear here. Pattern **E** ("naturalize") rows are
listed for completeness but are batch-approvable with no code at all.
Many rows still read "nerf" or "limit". A **Direction:** note (added
2026-10-10) gives the integrate-don't-disable direction (Protocol #8),
which supersedes the row's tentative note; see
[Integration directions](#integration-directions-2026-10-10). A row with
no Direction note that disables, caps or nerfs a feature is open: it
needs an integrate-don't-disable proposal before promotion.

Promote a row out of this section by writing it up in the integration
log above with the next sequential `#NNN`.

### Combat & weapons (1.9 onward)

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Off-hand slot | 1.9 | D | FC inventory has no slot 40; pair with FC torch/shield use |
| — | Attack cooldown bar | 1.9 | C | FC combat assumes instant repeat; cooldown halves DPS vs FC penalty model |
| — | Shields | 1.9 | D | FC variant: requires hide + iron, blocks % based on stamina |
| — | Tipped arrows / lingering potions | 1.9 | C | Lingering only craftable if FC potion stand |
| — | Trident + Riptide/Loyalty/Channeling | 1.13 | B | Gate behind drowned drop in FC nether/wild loot. **Direction:** drowned keep tridents as an FC variant; Riptide/Loyalty/Channeling come from drowned arcane scrolls |
| — | Smithing-template netherite upgrade | 1.20 | A or B | Re-tier netherite under Soulforged, or gate behind soulforge |
| — | Mace + Wind Charge | 1.21 | — | Future port only |

### Inventory, UI & player UX

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Recipe book | 1.12 | C | Reveals all FC recipes; only books FC explicitly grants. **Direction:** unlocks driven by the BTW advancement tree |
| — | Advancements (replaces achievements) | 1.12 | D | FC adds its own advancement tree as datapack. **Direction:** the tree mirrors BTW's tech ladder, answering the "wiki-dependent" complaint (triage CTX-3) |
| — | Spectator mode | 1.8 | C | Server gamerule disable in default. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Auto-jump | 1.10 | C | Default off — bypasses FC penalty-jump tuning |
| — | Held-item swap (F key) | 1.9 | C | Rebind to FC torch toggle |

### Movement & survival

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Sprint mechanics (revised) | 1.9 | — | Partially bridged in `PlayerBridge`; needs penalty-gate audit |
| — | Elytra | 1.9 | B | Gate behind soulforge fuel + cooldown; bypasses ground travel & HC Spawn |
| — | Powder snow + freezing damage | 1.17 | D | Route through FC `DamageSource`, allow leather-boots immunity |
| — | Scaffolding | 1.14 | C | Limit FC variant to N blocks of vertical traversal |
| — | Dolphin's grace effect | 1.13 | C | Cap duration |
| — | Soul speed enchant | 1.16 | B | Require FC reagent. **Direction:** comes from piglin / wither skeleton arcane scrolls |
| — | Bubble columns (magma/soul sand) | 1.13 | C | Rebalance flow — trivializes underwater mob farms. **Direction:** with BTW buoyancy, item elevators and buoyancy sorting instead of a flow nerf |

### Food & hunger

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Honey bottle | 1.15 | C | Low restore, FC alchemy reagent |
| — | Sweet berries / glow berries | 1.14 / 1.17 | C | Low restore, hostile bush damage |
| — | Suspicious stew | 1.14 | C | Lower buff tier |
| — | Composter | 1.14 | D | FC compost-bin variant; bypasses FC's hemp/dung composting chain. **Direction:** feeds BTW's dung/fertilizer chain instead of bypassing it (verify the 4.B chain) |

### Tools, tiers & enchantments

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Netherite tier | 1.16 | A | Re-tier under Soulforged; conflicts with FC's tier ceiling |
| — | Smithing templates | 1.20 | B | Require FC ingredient in template |
| — | Anvil rename / repair audit | pre-FC | — | Audit FC anvil overrides for parity with `MendingCapMixin` siblings |
| — | Grindstone disenchanting | 1.14 | C | Tax via FC reagent |
| — | Anvil XP cost cap (40) | 1.9 | C | Raise cap, FC charges differently |

### Blocks & materials

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Copper (block + oxidation) | 1.17 | D | FC repurposes for piping/wiring. **Direction:** melted in the crucible as an early metal; copper lightning rods weaken as they oxidize, honeycomb wax preserves them |
| — | Amethyst geodes | 1.17 | C | Slow regen rate to avoid free renewable |
| — | Lightning rod | 1.17 | C | Reduce range — negates FC lightning ignition. **Direction:** feed the same lightning mechanics as BTW's own rod (`FCBlockLightningRod`) instead of cutting range |
| — | Sculk catalyst/shrieker/sensor | 1.19 | C | Sensor wireless redstone shortcuts FC redstone. **Direction:** sculk as a soul source for urns, catalysts competing with soul hoppers; sensors hear running BTW machines, wool dampens them |
| — | Lodestone | 1.16 | B | Require FC nether material — compass-tracking trivializes navigation. **Direction:** integrate, don't nerf: BTW's random respawn makes lodestone navigation genuinely valuable |
| — | Conduit | 1.13 | C | Smaller radius, drains hunger — free Strength III + water breathing |
| — | Beacon (post-1.4 + soul beacon) | 1.4 / 1.16 | C | FC food cost, smaller pyramid base |
| — | Mangrove propagules / azalea | 1.19 / 1.17 | D | FC growth tick rules |
| — | Chiseled bookshelves | 1.20 | C | Comparator output signal nerf. **Direction:** powers the Infernal Enchanter by the books it holds, not a signal nerf |
| — | Bamboo wood family | 1.20 | A | Tier-align with FC wood progression. **Direction:** through the saw (sidings, mouldings, corners, bark) |

### Crafting & workstations

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Blast furnace | 1.14 | B or D | Recipe set restricted, or FC variant — bypasses bloomery progression. **Direction:** needs bellows or stoked heat |
| — | Smoker | 1.14 | C | Slower than vanilla, costs FC fuel |
| — | Campfire | 1.14 | C | Limited recipes |
| — | Datapack recipe / FC `CraftingManager` priority | 1.13 | — | Audit needed — could shadow FC recipes |

### Mobs

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Horse / donkey / mule | 1.6 | D | FC saddle + breeding rebalance. **Direction:** animal power: on a lead, turns a BTW axle (a tier between hand crank and windmill) |
| — | Llama / trader llama | 1.11 / 1.14 | D | Caravan mount + spit |
| — | Rabbit | 1.8 | C | Food value low |
| — | Aquatic life (turtle, dolphin, fish) | 1.13 | C | Drops slotted into FC food tiers. **Direction:** a turtle helmet blocks squid head-crabbing |
| — | Pillager / vindicator / ravager / evoker | 1.14 / 1.11 | C | Raid mob drops (totem, emerald, raid-specific items) — `#004` covered the outpost chest loot; mob-drop loot still open. **Direction:** pillager arcane scrolls carry Multishot / Piercing / Quick Charge |
| — | Bee | 1.15 | D | FC honey integrates with alchemy. **Direction:** bees pollinate hemp; honeycomb wax preserves copper lightning rods |
| — | Piglin / hoglin / zoglin / strider / piglin brute | 1.16 | D | Barter table FC-flavored. **Direction:** piglins barter BTW Nether goods; BTW nether growth spreads onto nylium; Soul Speed scrolls |
| — | Goat / axolotl / glow squid | 1.17 | C | Axolotl drops nerf, goat horn locked. **Direction:** goats ram through FC's `OnKickedByCow` hook and can turn an axle; axolotls hunt FC squid; glow squid kept as BTW's night head-crab squid (reverses the SW5 collapse) |
| — | Allay | 1.19 | C | Limit count — auto item-collect trivializes FC sorting. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Camel | 1.20 | D | Mount. **Direction:** animal power on a BTW axle |
| — | Sniffer + torchflower / pitcher plant | 1.20 | C | Low yield. **Direction:** under BTW husbandry, sniffers dig up hemp seeds |

### World generation

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | 1.7 biome rewrite | 1.7 | D | FC biome modifier datapack — `BiomeDecorator` only knows old biomes |
| — | Ocean monument / elder guardian | 1.8 | C | Mining fatigue + prismarine balance |
| — | Datapack worldgen (noise router) | 1.18 | D | FC datapack overrides — `WorldType` ignored |
| — | World height extension (-64 → 320) | 1.18 | — | Audit FC pathfinding & block IDs assuming 0–255 |
| — | Cave biomes (lush/dripstone/deep dark) | 1.17 / 1.19 | D | FC ore tweaks per cave biome. **Direction:** deepslate/tuff/calcite as BTW's deeper stone strata (seed: `ModernBlockStandIn`); the warden guards the richest soul source |
| — | Cherry biome | 1.20 | A | Biome + wood tier-align. **Direction:** cherry wood through the saw |
| — | Mangrove biome | 1.19 | A | Biome + wood + mud + propagules. **Direction:** mangrove wood through the saw; mud bricks sun-dried like BTW's unfired bricks |

### Trading & villages

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Profession workstation system | 1.14 | D | FC professions on workstations (in progress: `ContainerMerchant`) |
| — | Trade level XP / progression | 1.14 | C | Slow leveling |
| — | Hero of the Village | 1.14 | C | Disable or nerf — free discount + items. **Direction:** open; needs an integrate-don't-disable proposal before promotion (Protocol #8) |
| — | Cured zombie villager discount | 1.4 / 1.14 | C | Cap discount — trivial near-infinite trades |
| — | Iron golem auto-spawn from villagers | 1.14 | C | Require FC trigger — free defenses |

### Death, respawn & sleep

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Recovery compass | 1.19 | C | Require FC compass + reagent — trivializes lost-corpse retrieval. **Direction:** integrate, don't nerf: BTW's random respawn makes the compass (echo shards, ancient cities) genuinely valuable |
| — | Hardcore-mode spectator-on-death | 1.3 | — | Audit interaction with FC death override |

### Status effects & potions

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Slow Falling | 1.13 | C | Low brewing yield, expensive |

### Audio / visual / cosmetic

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | New particle types | 1.13+ | — | Partial: numeric ID translation — finish coverage in client bridge |

### Networking / data / commands

| # | Feature | Since | Pattern | Notes |
|---|---------|-------|---------|-------|
| — | Brigadier commands | 1.13 | D | Re-register FC commands as Brigadier nodes — `CommandBase` is old API |
| — | Datapack tag system | 1.13 | — | Audit — FC ID-based checks ignore tags. **Direction:** publish BTW tags (e.g. Forge ingot tags for soulforged steel) |
| — | Resource pack v2/v3/v4 textures | various | — | Partial via `BlockModelBridge` / `FCBakedModel`; finish coverage |
| — | World height in heightmap APIs | 1.18 | — | Audit FC code assuming 0–255 |
| — | IFluidHandler | Forge | D | Partial — wire FC fluid containers (cauldron, bucket, etc.) |
