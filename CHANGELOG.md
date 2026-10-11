# Changelog

Notable changes to the Better Than Wolves Forge 1.20.1 port. Versions are the port's own SemVer
(see `VERSIONING.md`); every release runs BTW **4.B0000003** (FlowerChild's 1.5.2 code).

> **Reconstructed 2026-10-10.** There were no tags and no changelog before this file. The history
> below is rebuilt from the CurseForge files list and the git history. Most uploads were built from
> uncommitted working trees, so for every release before 1.3.0-alpha.3 the git state named is the
> closest one, not an exact match. All five CurseForge files are marked "Release"; they are alphas.
> From now on, follow the release checklist in `VERSIONING.md` (changelog entry, `git tag`,
> CurseForge type "Alpha").

## [Unreleased]

Not released or committed yet. These fixes were made on 2026-10-10, in five implementation waves on
top of 1.3.0-alpha.3 (`9569443`). They address the CurseForge feedback triage and an earlier design
pass. `docs/curseforge-feedback-triage.md` has a status table for every finding, and the ids in
parentheses refer to it and to the design pass. Every wave builds and passes the link and bridge
audits, and a dedicated server boots a fresh world without errors. Nothing has been run on a client
yet (see Known issues).

### Fixed

Reported on CurseForge:

- **Ghast noises in the Overworld.** Every bat that took off played the ghast's loud warning sound
  ("Ghast shoots"), which carries 64 blocks. The cause: 1.5.2 sound-event ids were passed to 1.20.1
  unchanged, and the bat-takeoff id means "ghast warning" in 1.20.1. All 1.5.2 sound and particle
  event ids are now translated. This also removes a ghast fireball sound when a zombie infected a
  villager or a zombie villager was cured, and a firework sound when a torch fizzled out (GH1, GH3).
  Witches now idle with the quiet ghast scream BTW gives them instead of ghast moans, and they spawn
  naturally only in swamps, as in BTW (GH2).
- **Ink sacs and arcane scrolls in lakes.** BTW squids drowned on the Minecraft side about 25 s after
  entering water, and dropped their ink. BTW mobs now breathe only by BTW's own rules (D1). Arcane
  scrolls were rolled 20 times a second for living glow squids, drowned, husks, zombie villagers and
  wither skeletons. Scrolls now drop once, on death, with BTW's odds (D2), and those mobs are now
  replaced by BTW mobs anyway (see Changed).
- **Skeletons without bows.** Mobs that replace vanilla spawns now go through BTW's own spawn setup,
  so skeletons carry bows (spawner skeletons still have none, as in BTW), zombies can wear armour and
  sheep get BTW's wool colours. Mobs converted from an existing world keep their name, age, owner,
  collar, profession, equipment and health (SW1, HAI-6). Spawners count BTW mobs toward their limit,
  as in 1.5.2, so a dungeon spawner stops at 6 nearby mobs.
- **Zombies walking on water.** Water and lava were solid ground to BTW mobs. They now sink and swim
  (SW3). The same fix lets BTW mobs pass through tall grass, flowers, crops, fire, cobwebs, sugar
  cane, vines, redstone dust, levers, buttons, pressure plates, signs, tripwire and portals. Ladders,
  chests, slabs, fence gates, rails, repeaters, and BTW's own slabs, sidings and mouldings get their
  1.5.2 shapes. Plants and cobwebs slow BTW mobs, and cactus hurts them (SW-M1, partly; see Known
  issues).
- **Mobs stuck red, red textures, red XP, vanilla mobs "in a dying state".** Once a red "dragon" XP
  orb had been drawn, every BTW mob and many BTW models stayed dark red, and other colours leaked the
  same way. The renderer now resets its colour for every model, as 1.5.2 does (R1, D3). Dye colours
  now show too: sheep wool, wolf collars, dyed windmill sails, leather armour (R6). Player kills,
  one-hit kills included, drop normal green XP instead of a red orb that could not be picked up (R2).
  Mobs flash red when hit and tip over when they die, and creepers flash white before exploding, as
  in 1.5.2 (R4).
- **Mobs dying.** Besides drowning (above), damage BTW deals by itself (falls, fire, attacks by BTW
  mobs) now lowers the mob's real health and ends in a normal death (D6). The false suffocation
  damage left in 1.3.0-alpha.3 is now undone exactly, and its hurt sound no longer plays (R5). BTW
  mobs no longer fall through deepslate, tuff, granite and other blocks added after 1.5.2 (D4).
  Squids in seagrass and kelp count as being in water (D5). Wounded mobs no longer come back at full
  health after a reload (RE7). Blazes, ghasts, pigmen, magma cubes and wither skeletons no longer
  take damage in lava and fire (D8), and hitting a BTW mob no longer plays the player's "oof" (D-M1).
- **Hostile mobs not attacking.** After a player's first death, or after leaving the End, every BTW
  mob kept targeting the dead player until relog, and BTW's health penalty stayed at its worst. Mobs
  now attack after a respawn, and the respawned player starts with BTW's fresh state (full food, no
  penalties), as in 4.B (HAI-1). BTW mobs below y=0 (deepslate caves, superflat worlds) stood still
  instead of chasing the player. BTW pathfinding now covers the full 1.20.1 world height (HAI-3).
  Mobs near hay bales, terracotta, carpet or coal blocks could not find paths (HAI-4). Witches froze
  after drinking their first potion (HAI-5).
- **Creepers not exploding.** The AI scheduler cleared a creeper's target just before its fuse could
  start, so creepers stopped about 3 blocks away and never hissed. AI tasks now run in 1.5.2's order
  (HAI-2). Creepers visibly swell and flash, and a creeper kicked by a BTW cow becomes determined to
  explode, as in 1.5.2. Creeper explosions respect `mobGriefing` and are credited to the creeper
  (HAI-7).
- **Can't re-enter a world.** No BTW cause has been found. As hardening, BTW's world, player and
  block-entity save, load and unload code can no longer throw out of the save or shutdown path, and
  BTW's server-side state is released when a world closes (RE1, RE-M2, RE3). If you still cannot
  re-enter a world, please report it with `logs/latest.log` (see Known issues).
- **Buggy animations.** The mob renderer is now a full port of 1.5.2's:
  - chicken wings, wolf tails and squid tentacles no longer spin;
  - mobs swing their arms and show held items and armour;
  - sheep have wool and lower their heads to graze;
  - slimes have their sizes, and baby mobs are small;
  - endermen have eyes and carried blocks, and zombie villagers have the villager head;
  - withers, wither skulls and magma cubes are drawn at all;
  - squids are no longer turned twice (R7, SW2, SW6).

  Clients now animate BTW mobs every tick instead of replaying two-tick-old snapshots, so legs no
  longer stutter and wolves shake dry (R8; a few animations are still missing, see Known issues).
  Withers have a boss bar and turn their side heads.
- **Little in-game information.** BTW 4.B teaches almost nothing in game, by design, but the port
  made it worse. JEI now covers the soulforge, the campfire, BTW's special crafting recipes and the
  processes that have no recipe list, and BTW's join messages are back (see Added). The CurseForge
  page no longer promises a "field manual". The wiki link is still to be added (CTX-3 to CTX-6).

Other fixes:

- **Mining speed.** Every BTW block broke at the same speed with any tool: rough stone took about
  3.4 s by hand or with an iron pick. BTW's penalties never slowed mining. Digging now uses BTW's own
  formula on both server and client, so BTW blocks are slow by hand and fast with the right tool, and
  health, hunger, fat and gloom penalties slow it (B3). On a dedicated server, the client's dig
  prediction ran FC hardness against a missing world and could crash when mining stone, ore or logs
  (B3-CLIENT; found by code review, not reproduced). Mining deepslate, tuff or another block added after 1.5.2
  with a tool could crash a single-player game. It no longer does, and BTW tools now wear on those
  blocks.
- **Penalties.** The melee damage penalty compounded up to four times on a hit, and was applied once
  more to all damage the player dealt, arrows included. It now applies once, to melee only (B1).
  The slowdown stayed after a penalty had cleared, until relog or death. It now follows the current
  penalty (B2). BTW's block speed modifiers now reach players (B4, see Changed).
- **Food.** Raw meat, raw fish, melon, mushroom stew and rotten flesh restored hunger twice: BTW's
  value plus Minecraft's. Every food now restores hunger once. For example, raw beef gives 12 points
  of BTW's 60-point hunger bar instead of 21. Carrots and potatoes restore BTW's 3. Golden apples,
  spider eyes and poisonous potatoes now apply their BTW effects. Other vanilla foods now use BTW's
  values (see Changed).
- **Bowls and drink containers.** Chicken soup, chowder and hearty stew never gave their bowl back.
  They now return one, as in 1.5.2. With a full inventory, mushroom stew gave two bowls and a potion
  two glass bottles; both now give one. Drinking BTW chocolate milk lost the bucket. In creative
  mode, chocolate milk is no longer used up, eating no longer plays BTW's "can't eat" sound, and a
  full inventory no longer produces free bowls.
- **Crafting.**
  - FC recipes no longer vanish from the recipe book on every login, and recipe-book auto-fill works
    for them. They are now injected into both of the recipe manager's indexes (RE6).
  - FC recipes are no longer lost after `/reload`; they are re-injected after every datapack reload.
  - The player's 2x2 inventory crafting grid now follows FC's crafting rules, as in 1.5.2:
    - tools used as ingredients (shears, chisels, axes) lose durability instead of being used up
      (not in creative mode);
    - moulds and other reusable items stay in the grid;
    - FC's per-ingredient effects happen: sawdust and bark from chopping logs, pumpkin seeds, gravel
      from splitting stone with the iron chisel, string from breaking a bow, the axe chop sound.
  - FC's code-driven crafting recipes (log chopping with an axe, knitting, baiting a fishing rod) now
    work in the 2x2 inventory grid. Before, they only worked at a crafting table. The knitting recipe
    only starts the knitting; the knitting itself still cannot be finished (see Known issues).
  - Ingredients that accept any variant now accept every variant in every grid, at the soulforge, in
    the recipe book and in JEI. Before:
    - "any planks" meant jungle planks only in the 2x2 grid, and never matched at the crafting table;
    - shaped recipes that call for a block (planks in the axle and the hamper) took only its first
      variant;
    - the 3x3 wool block could not be crafted anywhere.
  - Wool blocks and wool armour get their blended colour again. BTW's per-ingredient effects now
    happen at the crafting table for every recipe, not only for log chopping.
  - A mould left in the soulforge grid no longer blocks recipes.
  - An FC recipe whose ingredient has no 1.20.1 equivalent is now skipped instead of being added
    without that ingredient.
- **Items and inventory.**
  - Right-clicking a BTW block, pressing a button in a BTW container or hitting a mob with a BTW tool
    could change other items in the inventory: wool changed colour, spruce, birch and jungle logs
    turned to oak, names were lost, spawn eggs were deleted, BTW tool wear was undone, and stacks
    picked up a stray tag and stopped stacking. Only the slots BTW actually changes are written back
    now. Wool, wood species, dyes, coal and charcoal, skulls and spawn eggs convert correctly (U1).
  - Shift-clicking into or out of BTW containers could delete or duplicate items, pressing Q on some
    BTW container slots deleted an item, and enchantments added by BTW could be lost (U1b, U2).
  - Items added after 1.5.2 that are stored in BTW containers are no longer lost or swapped after a
    game restart (RE5).
  - The global (tier-3) ender chest is no longer emptied on reload (RE3).
  - Minecraft enchantments now count in BTW code. Silk Touch, Fortune, Looting, Efficiency,
    Unbreaking and Protection affect BTW harvests, drops, digging, wear and armour, and the Infernal
    Enchanter counts them toward its limits.
- **Dropped items.** BTW could not see dropped items at all. BTW hoppers, cauldrons and crucibles now
  collect items dropped on them, animals go for loose food, campfires burn dropped fuel, bellows blow
  items around and cactus destroys them (U3).
- **Bows and projectiles.**
  - The vanilla bow fired two arrows per shot, BTW's and Minecraft's. It now fires one.
  - The BTW composite bow can be drawn, and composite-bow and broadhead arrows no longer vanish when
    picked up.
  - BTW throwables (soul urns, dynamite) threw two per click and were never used up. Now one is
    thrown and one is used.
  - BTW arrows, thrown items and fireballs flew through vanilla mobs, boats and end crystals. They
    now hit them and credit the shooter.
  - Arrows and fireballs from BTW mobs hit players as projectile damage, so shields, Projectile
    Protection and "shot by" messages work. BTW mining charges deal explosion damage, so Blast
    Protection works.
- **Using items.** BTW right-click actions from the off hand ran against the main-hand item, so an
  off-hand seed could empty the main-hand slot. BTW item actions now use the main hand only. In
  creative mode, BTW seeds, hoes and flint and steel were used up or damaged; they no longer are.
  The breeding harness, dung and dye now work on BTW animals, and feeding a BTW animal uses up the
  item.
- **Blocks and rendering.**
  - BTW blocks now render with their real neighbours and contents: table and bench legs; connecting
    grates, slats and panes; platform rails; door halves; cauldron and crucible contents.
  - Wheat crops and weeds, blood-wood stairs, the BTW chest and placed tools were invisible and now
    show. Smooth-stone stairs have their texture. The wicker basket shows its stored item and its lid
    opens (U7, U7b, U8).
  - Blocks that mobs hold, wear or carry are drawn centred, in 3D and with the right textures: a
    zombie's cobblestone, a snow golem's pumpkin, an enderman's cactus or grass block. Held torches
    and flowers are flat icons.
- **World.**
  - BTW's world clock was stuck at 0, so BTW chickens never laid eggs and torches carried in the
    inventory never burned out. BTW's Nether, Wither and End progress was not saved either (U9).
  - Snow layers on stone, dirt or grass vanished whenever a neighbouring block changed, and burning
    leaves never left ash.
  - Overworld nether portals spawned zombified piglins, which BTW prevents (GH5).
  - Penned animals no longer hop over fences and walls.
  - A BTW cow's kick threw an error that cut its update short (U6).
  - A summoned wither despawned when players moved away.
  - BTW mobs skipped a tick when landing (HAI-10).
- **Shipped jar only.** The 1.3.0-alpha.3 jar contained old copies of 16 internal classes that
  development builds never ran. As a result, the anvil and enchanting table could crash the game,
  some chest windows showed every slot twice, and shift-crafting gave items without using up the
  grid. The jar now uses the same classes as development builds, and the build fails if they drift
  apart (E1, RE-M3).
- **Entity names.** Death messages and commands showed raw keys such as
  `entity.betterthanwolves.fc_skeleton`. Every BTW entity now has a name.
- **Logging.** The `[MOB-DMG]` warning on every hit is gone, along with other leftover diagnostics
  that logged on every tick, block change or screen open (U10). Real failures are logged once per
  code location with a stack trace, instead of being hidden or sampled (HAI-10).

### Changed

Most of these restore BTW 4.B behaviour, but players will notice them.

- **Food values follow BTW 4.B.** Vanilla foods that BTW rebalanced now restore BTW's values instead
  of Minecraft's. Values are points of BTW's 60-point hunger bar (6 points = 1 shank):

  | Food | Now | Was |
  |---|---|---|
  | Bread | 9 | 15 |
  | Cooked porkchop, steak | 15 | 24 |
  | Cooked chicken | 12 | 18 |
  | Cooked fish | 12 | 15 |
  | Baked potato | 6 | 15 |
  | Pumpkin pie | 6 | 24 |
  | Cookie | 3 | 6 |
  | Apple | 3 | 12 |
  | Golden carrot | 3 | 18 |
  | Golden apple | 3 | 12 |

  Saturation follows BTW too. The golden apple gives Regeneration I for 5 s instead of Regeneration
  II and Absorption. The enchanted golden apple gives BTW's Regeneration IV, Resistance and Fire
  Resistance. Cookies and pumpkin pie can be eaten when full, BTW wolves eat cooked meat, and BTW
  pigs eat baked potatoes.
- **Food stack size inside BTW.** BTW's own item handling (its hoppers, for example) now stacks these
  foods to 16, as 4.B did and as it already did for raw meat. The player's inventory and Minecraft
  containers still stack them to 64 (see Known issues).
- **Movement.** BTW's block speed modifiers apply to players (B4):
  - about 20% faster on hard surfaces;
  - 80% speed on sand, farmland, loose snow and plants;
  - half speed when walking on leaves.

  BTW's penalties now also slow you in the air, as in 1.5.2.
- **Mob spawns.**
  - 1.20.1's drowned, husks and zombie villagers spawn as BTW zombies, strays and wither skeletons as
    BTW skeletons, and glow squids as BTW squids. Drowned therefore bring BTW zombies to rivers and
    oceans, which BTW never had.
  - Naturally spawned zombie villagers become plain zombies, because BTW makes villager zombies only
    when a zombie kills a villager (SW5).
  - Witches spawn naturally only in swamps (GH2).
- **Blocks added after 1.5.2 are solid to BTW.** BTW mobs stand on and path around deepslate, tuff
  and the other new blocks. BTW sand rests on them, BTW torches attach to them, BTW pistons cannot
  move them and mining charges destroy them. Seagrass and kelp count as water (U4, D5).
- **BTW blocks that had no collision in 1.5.2 are walk-through again:** rails, plants, torches,
  campfires, candles, cobwebs (which slow you down), ground cover and hand cranks. Lit campfires and
  stoked fire burn players and Minecraft mobs that touch them, as in 1.5.2. Minecraft water can now
  wash some of these blocks away.
- **Slabs and chests.** Single slabs are half blocks to BTW, as in 1.5.2. BTW sand or gravel on a
  bottom slab can fall. BTW torches and other attachments on a slab's side or on top of a bottom slab
  drop on the next block update; this affects existing builds. Single slabs no longer turn on
  turntables. Chests no longer count as solid blocks for BTW attachments and turntables.
- **Mobs pick up dropped items**, as in 1.5.2. BTW villagers, snow golems, iron golems, dire
  wolves and mobs carrying picked-up loot no longer despawn when you move away.
- **Overworld nether portals** now let BTW possess nearby creatures, as in 4.B.
- **Player name tags.** In multiplayer, other players' name tags are hidden by default: this is BTW's
  "Hardcore" player-names option. Set `fcEnableHardcorePlayerNames=0` in the server's `BTWConfig.txt`
  to show them, or `2` to hide them only behind walls (CTX-5).
- **Projectile damage** from BTW mobs on players is no longer scaled by difficulty, as in 1.5.2.
- **Existing saves.** The first time an existing world loads, torches in inventories may burn out
  and fed chickens may lay at once, because BTW's world clock now runs.
- **Network.** The server and every client must run the same build of this port (network protocol
  3). 1.3.0-alpha.3 clients and servers are refused.
- CurseForge description:
  - removed the "field manual" instruction;
  - filled in the issue tracker;
  - marked the wiki and original-BTW links as maintainer TODOs;
  - described the actual JEI coverage;
  - added a "Known Differences from BTW 4.B" section, updated for this release (CTX-4).

### Added
- JEI: soulforge and campfire recipe categories, FC's special crafting recipes in the crafting
  category, and information pages for the kiln, saw, turntable, hopper filters and souls,
  hibachi/stoked fire, cauldron/crucible fire rules and campfire cooking (CTX-6).
- FC's login handshake runs again (CTX-5):
  - single-player worlds show "BTW V4.B0000003" on joining;
  - servers show FC's connect line, "BTW version check successful." (or BTW's version-mismatch
    warning) and the "Hardcore Modes" line, and then apply the server's player-names option.

  A one-time note per port version points to the issue tracker.
- BTW iron golem (`fc_iron_golem`) and silverfish (`fc_silverfish`) entity types, with their 1.5.2
  models. Before, they borrowed the vanilla villager and zombie types and turned into BTW villagers
  and zombies after a reload.
- Worlds save `data/btw_headless_items.dat`, the ids of items added after 1.5.2, so BTW containers
  keep those items across restarts (RE5).
- Development tooling:
  - `tools/LinkAudit.java` checks the class precedence of the built jar and reports Minecraft class
    references in frozen BTW code;
  - a `jar` task guard fails the build on precedence drift;
  - `tools/BridgeAudit.java`'s texture check understands the `%blur%`/`%clamp%` prefixes and the
    entity renderer's vanilla fallbacks.

### Known issues
- **Not tested on a client yet.** Only a dedicated server has been run: a fresh world, twice. It
  boots with no errors, BTW initialises and vanilla mobs are replaced. Every rendering fix (red tint,
  mob renderers and animations, held items, chests and baskets) and every other client-side change
  is checked by code review only. Multiplayer is untested too.
- **Re-entering a world.** No cause was found. It has not been tested whether a world now always
  shuts down cleanly and releases its `session.lock`. If a world will not open again, please report
  it with `logs/latest.log` and any crash report.
- **Knitting, wicker weaving and bone carving never finish.** Holding right-click makes no progress,
  because BTW's hold-to-craft progress (`FCItemCraftingProgressive.UpdateUsingItem`) is not bridged.
  So you cannot weave wicker, knit wool or carve a bone fishing hook. 1.3.0-alpha.3 has the same
  problem.
- Some mob animations and effects are still missing: squid tentacle movement, the cow's kick, the
  squid's tentacle attack, and villager and witch particles (R8).
- BTW's Hardcore Buoy option has no effect: dropped items float as in 1.20.1. The "Obstructed"
  player-names option hides only players' name tags behind walls, not named mobs'.
- Vanilla 1.20.1 crafting recipes that BTW 4.B removed can still be crafted, because BTW's recipe
  removals are not applied.
- Foods stack to 64 in the player's inventory, not to BTW's 16.
- BTW mobs do not trigger pressure plates or tripwire, do not travel through nether or end portals,
  and are not slowed by soul sand. Moving piston blocks are solid cubes to them (SW-M1).
- Lightning does not charge BTW creepers or turn BTW pigs into zombie pigmen: Minecraft's lightning
  strikes are not passed to BTW mobs (HAI-M1).
- The vanilla bow fires Minecraft's arrows and draws with arrows from anywhere in the inventory. 4.B
  fired BTW arrows, from the hotbar only. BTW's arrow code runs only for rotten arrows and for the
  composite bow.
- When BTW stores or changes items that 1.20.1 split into separate items, some come back as their
  1.5.2 equivalent:
  - black, brown, blue and white dye become ink sac, cocoa beans, lapis lazuli and bone meal;
  - smooth sandstone becomes cut sandstone;
  - stone slabs become smooth stone slabs;
  - blue orchids, tulips, wet sponge and coloured beds collapse the same way.
- BTW's own items can only be used from the main hand.
- Minecraft water can wash away BTW vine traps and placed buckets, which 1.5.2 protected.
- Minor visual and sound gaps:
  - the composite bow shows no draw animation;
  - placed tools show their 1.20.1 texture;
  - the mining charge's flash is not drawn;
  - mobs picking up items show no animation;
  - the millstone's and wet cement's ambient sounds are silent.

## [1.3.0-alpha.3] - 2026-07-09

CurseForge: `BetterThanWolves-1.20.1-1.3.0-alpha.3.jar`, uploaded 2026-07-09. Git: `9569443`
(version bump only); the jar was built from the tree at `a832245`, seven seconds before the bump
was committed. The version jumped from 0.4.0-alpha.3 (`2d2119f`, never uploaded) straight to
1.3.0-alpha.3; there are no 0.4-1.2 releases.

Everything since the 0.3.0-alpha.3 upload, including the 2026-06-05 re-audit:

### Fixed
- Bridge link crashes (including the soulforge crash), FC armor registering as real armor, and
  FC foods that could not be eaten or gave nutrition twice (`d7e7793`).
- Entity registry crash on start-up (missing Hopper class) (`1586ddc`).
- Movement modifiers applied twice ("super fast" walking) (`20e5603`) and sprint "flying"
  (`f9d9b6f`); the movement penalty is now also applied on the client (`2d2119f`).
- FC container clicks needing two clicks, and dragging stacks across slots (`2171f6a`).
- FC mobs rubber-banding (`7c2a417`).
- Blocks ignoring their orientation when placed, e.g. axles (`aa978b8`).
- Spruce, birch and jungle logs, leaves, planks and slabs dropping as oak (`a2f259b`).
- Lightning rod collision box (`7c6ba11`).
- Two systemic FC block render bugs: a GL11 matrix leak and invisible colour-multiplied
  geometry (`0c6a314`); stoked-fire textures (`9f75ec5`); soul urn textures (`992f396`,
  `996e33d`).
- FC mobs dying at random from spurious suffocation (`ac6b20e`, `6c99d20`, `534884e`, `a832245`).
  The last two also targeted the red tint, but they did not fix it (see Known issues and triage R4).

### Added
- FC melee damage modifier wired up (`992f396`).
- Renderers for XP orbs, arrows, snowballs, eggs, TNT and the fishing hook (`bd8f23f`, `1aa697a`).
- Animated stoked-fire flame using FC's own procedural fire (`bd22da9`, `52d6a06`).
- Worldgen: FC lightning rod in jungle temples (`0cf6d4d`); FC bonus basket replaces the
  bonus chest (`b5cbc43`, `a177876`, `8aeec60`).
- Village subsystem and many verified stub implementations (`8bc4ccd`, `f96c2ec`, `35d9d96`).
- Build/launch audit tooling (`9fda783`, `836bbf7`).

### Known issues (found after release)
- Mobs and some textures turn dark red once an unowned XP orb has been drawn; the XP-orb
  renderer added in this release leaks its colour (triage R1).
- Every real hit on an FC mob is logged at WARN (`[MOB-DMG]` diagnostic left in).
- See `docs/curseforge-feedback-triage.md` for the full list.

## [0.3.0-alpha.3] - 2026-05-16

CurseForge upload of 2026-05-16. No commit matches it: the 0.3.0-alpha.3 label was only
committed on 2026-07-08 (`d7e7793`), together with later work. The jar was built from an
uncommitted tree after `39471c1`; its exact contents are unknown. It does **not** contain the
2026-06-05 bridge fixes, the June armor registration, or any 2026-07-08/09 fix.

## [0.3.0-alpha.2] - 2026-04-21

CurseForge upload of 2026-04-21. Closest commit: `39471c1` (2026-04-20, "Patched some UI
elements"), which set this version.

## [0.2.0-alpha.2] - 2026-04-13

CurseForge upload of 2026-04-13. No commit ever carried this version; the jar was built from an
uncommitted tree between `71d3df0` (2026-04-12, "Added entity movement") and `39471c1`.

## [0.1.0-alpha.1] - 2026-04-05

First CurseForge upload. The version label was only committed on 2026-04-12 (`71d3df0`); the
jar was built from an uncommitted tree whose closest commit is `883ea4b` (2026-03-30). It is the
first public build of BTW 4.B0000003 running on the Forge 1.20.1 bridge (server and client), with
item textures and models, partial block models and UI, hunger and exhaustion, entity rendering
and the furnace brick (git, 2026-03-18 to 2026-03-30).
