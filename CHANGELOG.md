# Changelog

Notable changes to the Better Than Wolves Forge 1.20.1 port. Versions are the port's own SemVer
(see `VERSIONING.md`); every release runs BTW **4.B0000003** (FlowerChild's 1.5.2 code). The port's
rule is that this code is read-only: the port never edits FlowerChild's code and makes its changes
around it.

> **Reconstructed 2026-10-10.** There were no tags and no changelog before this file. The history
> below is rebuilt from the CurseForge files list and the git history. Most uploads were built from
> uncommitted working trees, so for every release before 1.3.0-alpha.3 the git state named is the
> closest one, not an exact match. All five CurseForge files are marked "Release"; they are alphas.
> From now on, follow the release checklist in `VERSIONING.md` (changelog entry, `git tag`,
> CurseForge type "Alpha").

## [Unreleased]

Not released yet. These fixes were made on 2026-10-10, 2026-10-11, 2026-10-14 and 2026-10-15, in eight
implementation waves on top of 1.3.0-alpha.3 (`48e9825`). The five waves of 2026-10-10 address the
CurseForge feedback triage and an earlier design pass; they were committed as `f6ec9be`, and `d55db5d`
set the version to 1.4.0-alpha.5. The 2026-10-11 wave fixes bugs the maintainer found in mechanical
power, sounds and the BTW creative tab, after an audit that proved each root cause against BTW's 1.5.2
code before anything was changed. The 2026-10-14 wave makes BTW's own rules decide how blocks that keep
a Minecraft id grow, spread, decay, burn and fall; its root cause was proven with a scripted in-world
test on a dedicated server before anything was changed, and the result was checked the same way
afterwards. The 2026-10-15 wave carries out three rulings by the maintainer: bone meal works as in 4.B,
plants added after 1.5.2 follow the 4.B rules of their nearest BTW relative, and blocks that 4.B removed
stay removed. It was checked the same way, with scripted in-world tests before and after the change.
`docs/curseforge-feedback-triage.md` has a status table for every triage finding; the ids
in parentheses refer to it, to the design pass and to the 2026-10-11 audit (`STUB_CATALOGUE.md`
entries (an) to (at)). The 2026-10-10 and 2026-10-11 waves build and pass the link and bridge audits,
the 2026-10-14 wave builds and passes the link and bridge audits, the 2026-10-15 wave builds and passes
the link audit, and a dedicated server boots a fresh world without errors. None of the client-side fixes
has been checked in game yet (see Known issues).

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

Reported by a player (2026-10-18):

- **Knitting, bone carving and wicker weaving never finished.** Holding right-click on knitting, a
  carving bone or wicker weaving made no progress, so wool knit, the bone fish hook (and with it the
  4.B fishing rod) and wicker could not be made. BTW's per-tick hold-to-craft step
  (`FCItemCraftingProgressive.UpdateUsingItem`) was never called, and BTW read a copy of the use timer
  that never moved. Both are bridged now: the progress bar fills and the item finishes on 4.B's timing
  (2 minutes for knitting and carving, 1 minute for wicker, after 4.B's short warm-up). Letting go
  keeps the progress. BTW's crafting sounds and particles run through the same step again (checked by
  code review only). Eating, drinking and bows are unchanged. Checked in game with a real client
  holding right-click on knitting and a carving bone, and on a dedicated server for all three.

Found by the maintainer (2026-10-11):

Each of these was traced to a shared part of the port that did not do what BTW's own 1.5.2 code
does, and was fixed there rather than block by block.

- **Severe server lag, and blocks breaking many seconds late.** After a few minutes a world with
  underground water fell further and further behind (up to 28 seconds), and logs and other blocks
  that take time to mine broke 10-30 seconds after you finished mining them. The port turns glow
  squids into BTW squids, but counted them against the ordinary squid limit, so the game never saw
  the underground-water limit as reached and kept spawning them: a fresh world held thousands of BTW
  squids in cave water within ten minutes. Mobs that replace a vanilla mob now count against the
  spawn limit of the mob they replaced. The slow block breaking was a symptom of the lag (Minecraft
  measures mining progress in server ticks). Worlds where squids have already piled up are not
  cleaned up; `/kill @e[type=betterthanwolves:fc_squid]` clears them.
- **Clutches and gear boxes ignored their facing.** A clutch or gear box always showed its input face
  on the south side, whichever way it was placed, and never showed its output faces, so axles got
  attached to the wrong side and the block seemed not to connect. The fronts of the bellows, saw,
  screw pump, block dispenser, buddy block, detector block, companion cube and mining charge never
  turned either. BTW blocks are now drawn with BTW's own per-position textures and 1.5.2's face
  rules, so every block shows the face it really has (MPR-1, F1). The power logic itself was already
  right.
- **Axle textures.** Axles running east-west or up-down had their end and side textures swapped, axle
  sides were textured the wrong way round, and no axle ever looked powered. Axles now look as in 4.B
  on every axis, powered axles and axle power sources show their moving texture, and axles a few
  blocks from a gear box switch their look across chunk-section borders too (MPR-2, MPR-6). The saw
  blade and the turntable's switch faces are drawn the right way round.
- **Animated textures.** A grinding millstone, a running saw blade, a powered axle and the water in a
  bucket showed their whole animation strip squashed into one face. They animate again (MPR-4).
- **Block items.** BTW block items looked like the placed block's first variant: the axle item was an
  upright post, the saw item faced down, every siding, corner, moulding and wool slab colour looked
  the same, and torches and crops were 3D blocks. Items now use BTW's own item drawing for each
  variant: a horizontal axle, a saw with an upright blade, stair-shaped stairs, the gear box showing
  its input, flat torches and crops. Held BTW block items face as in 1.5.2 (MPR-3).
- **Placing blocks.** Placed against a non-BTW block, BTW stairs always faced the same way, and the
  anvil, wicker basket, hamper and piston shovel ignored which way you were facing; placed against a
  BTW block they turned correctly. BTW blocks now go through BTW's block placement steps whatever you
  click, so they face the way you place them, variant items place their own variant and a lens sets
  up its beam (MPR-5). Clicking ash replaces it, as in 1.5.2.
- **Turntables.** Saws, bellows, block dispensers, screw pumps, buddy blocks, lenses and other blocks
  never turned on a turntable, and gear boxes, clutches and stairs turned the opposite way from
  pumpkins, repeaters and pistons. They now turn, all in 1.5.2's direction. Right-clicking an
  unconnected clutch or gear box cycles its facing in 1.5.2's order, clay shaped on a turntable keeps
  its clay, and the tops of anvils, baskets and hampers turn with them (F2).
- **Redstone power.** The redstone clutch, pulley, block dispenser and turntable reacted to weak
  redstone power that 4.B ignores: a clutch next to a redstone block, or next to a block powered by a
  lever or by dust, stayed disengaged. They now react only to strong power, as in 4.B: a redstone
  torch below, a repeater or dust pointing into the block, or a lever or button on it (F4).
- **Buddy blocks** did not react when a lever next to them was flipped, a vanilla block next to them
  was mined or a block added after 1.5.2 changed: BTW blocks were told "no block" for any neighbour
  that was not a BTW block. They now get the neighbour's id, as in 1.5.2. BTW blocks that alert their
  neighbours on purpose (the gear box, clutch and saw when they change facing, the BTW chest, stake
  strings, soul urns) now actually reach them (F3).
- **Bellows.** A player standing on a powered bellows sank into it when the power went off; the
  bellows lifts the player again, as in 1.5.2.
- **Sounds.** BTW sounds now play when, where and for whom 1.5.2 played them: every BTW sound name
  plays its own 1.5.2 sound file, sounds 1.5.2 played only on the client play there again, and the
  player who caused a sound hears it once (S1 to S11).
  - Back after being silent: the millstone grinding and starting up, the gear box clicking and
    smoking when powered, the bellows, the powered axle's creak, the turntable click, campfire and
    smouldering-log crackle, the wet cement's moan, stoked fire; zombie pigmen, enderman idle, the
    wither's idle and hurt sounds, ghast deaths, wolves shaking dry, ocelots, iron golems throwing,
    zombie curing, the dragon's wings, firework launches, the soulforged helm's breathing; BTW's
    crafting sounds for tools, armour, shears, cured food and other items, in the inventory grid, at
    the BTW workbench and at the soulforge; BTW mobs splashing into water; the burp after eating;
    the gloom ambience (cave sounds, growls, the enderman stare); grunting when walking exhausted or
    badly hurt; soulforged boot steps; spider webs and thrown soul sand.
  - Fixed after playing the wrong sound: the saw and millstone used a minecart loop, a hurt creeper
    hissed its fuse, the bellows mooed at random, chests, doors, anvils, the player's "oof" and the
    arrow-hit ding used 1.20.1's replacements, and BTW's own effects (hopper closing, redstone
    clicks, XP, wolves eating, melons and pumpkins bursting, lightning) had the wrong sound, volume
    or pitch and no particles.
  - Squishy BTW blocks (unfired pottery and bricks, rotten flesh, nether growth, BTW webs) squish,
    and glassy ones (crucible, vase, planters, light bulb) sound like glass, instead of stone. BTW
    mobs walking on blocks added after 1.5.2 use that block's footstep sound.
  - Other players now hear and see you break blocks. You no longer hear a block you place twice, and
    placing plays the place sound at 1.5.2's volume and pitch. Vanilla foods that BTW feeds you end
    with one burp and no extra eating sound.
- **BTW creative tab.** The tab listed every BTW block and item, including technical ones 4.B never
  showed: burning and lit blocks, crops, the axle power source, stoked fire, the milk and bucket
  blocks, "Block 1091", 20 old "Fc ..." siding and moulding blocks, decorative blocks all named
  "Aesthetic Opaque" (most likely the reported "Fc Opaque Generic"), and the old slats, grate and
  wicker items. At the same time 33 variants were missing, among them the 11 decorative blocks (block
  of soap, padding, hellfire, rope, flint, ...), 15 wool slab colours, the grass and packed earth
  slabs and the blood wood sapling. The tab is now built from BTW's own creative-inventory rules and
  lists what 4.B listed: 355 entries (CT1, CT2, CT4).
  - Blocks have their BTW names, per variant: Block of Soap, Soulforge, Brick Oven, Piston Shovel
    (CT3).
  - Variants place the right block: a creative Block of Soap places soap, and crafted packed earth
    and grass slabs place their own slab.
  - The vanilla Search tab and JEI list exactly what the BTW tab lists, and JEI shows each variant
    on its own: 22 arcane scrolls; 16 wool, candle and knit wool colours; 4 bark types (CT5).
  - Middle-click pick block gives what 4.B gave (a stick from a shaft, seeds from a crop, the idle
    brick oven, the kiln's drop) instead of the technical block.
- **Placed leaves decayed.** Leaves placed against most blocks decayed like natural leaves. They now
  keep 1.5.2's player-placed flag and stay.
- **Blocks out of sync.** When nether growth spread over netherrack, or a sapling failed to grow
  into a tree, the client kept showing the old block (netherrack, or nothing where the sapling
  stood). Both now update at once.
- **Placement errors.** An error inside BTW's placement code could crash the game or the server. It
  is now logged once and nothing is placed.

BTW's rules for plants, fire and falling blocks (2026-10-14):

Blocks that keep a Minecraft id, such as saplings, crops, grass, leaves, fire and sand, ran Minecraft
1.20.1's rules instead of BTW's, because the port's hook never reached them. In a test world all 25
birch saplings became trees within 30 seconds, and saplings in the End grew too. BTW's own rules now
decide for every one of these blocks:

- **Trees** grow from saplings at BTW's slow rate, need light and never grow in the End. They grow into
  BTW's trees, big oaks and 2x2 jungle trees included, and they also grow below y=1 (superflat worlds,
  deep caves): BTW's tree code only knew 1.5.2's world, which started at y=0.
- **Crops and other plants** grow at BTW's rates and only under BTW's conditions. Wheat, carrots and
  potatoes need light and watered soil and stop growing while weeds grow. Pumpkin and melon stems, sugar
  cane, cactus, cocoa and vines follow BTW's rates. Nothing grows in the End, and nether wart grows only
  in the Nether. Pumpkin stems grow BTW's own pumpkins, and pumpkin and melon stems stay attached to
  their fruit.
- **Crop drops follow BTW.** Breaking or harvesting unripe wheat, carrots or potatoes drops nothing.
  Ripe wheat drops wheat and 1-2 seeds, ripe carrots and potatoes drop 2-3, and a ripe potato plant
  drops a poisonous potato 1 time in 50. Ripe nether wart drops 2-4. Before, every crop dropped its item
  plus wheat seeds at any stage.
- **Grass** grows back on loose dirt again. It spreads only in daylight and never in the End, and grass
  that gets too little sky light turns back into dirt. Tall grass spreads onto unused farmland.
  Mycelium spreads onto loose dirt and dies in the dark.
- **Brown mushrooms** spread in complete darkness (or on mycelium) and give off no light, as in BTW.
  Their faint 1.20.1 glow had kept them from ever spreading. Mushrooms on mycelium sometimes grow into
  huge mushrooms (see Known issues for their textures).
- **Leaves** decay the BTW way: only after a log or leaves near them are removed, and only when no log
  is within 4 blocks. They drop only saplings (1 in 20, jungle 1 in 40), never apples or sticks.
  Stripped logs and wood (bark blocks) of oak, spruce, birch and jungle hold up leaves and cocoa pods as
  logs do.
- **Fire** burns by BTW's rules: logs smoulder instead of burning away, BTW's wicker and wooden blocks
  burn, and the fire on a lit hibachi keeps burning. Fire on magma blocks keeps burning, as in 1.20.1.
  Blocks added after 1.5.2 that burn in 1.20.1 (acacia, cherry and bamboo wood, hay bales, ...) still
  burn.
- **Lava** follows BTW: it lights BTW campfires and unlit torches next to it, melts BTW snow and starts
  fires at BTW's rate (1.20.1's fire spread ran twice for every lava block it picked).
- **Falling blocks.** Sand, gravel and the dragon egg no longer float on torches and other blocks they
  can fall through, and they land as BTW falling blocks. Anvils no longer fall, as in BTW. BTW's loose
  blocks (loose dirt, loose cobblestone, ...) now also fall below y=0.
- **Stone bricks** with water above them slowly turn mossy, and with lava above them cracked, when
  there is open space below them, as in BTW.
- **Plants on BTW soils.** Cactus on planters, sugar cane on loose dirt and crops on BTW farmland are no
  longer destroyed when a block next to them changes. Plants that 1.20.1 grows on its newer soils (grass
  on moss, mushrooms on podzol and nylium, cactus on red sand, ...) stay. Farmland that is trampled or
  covered turns into BTW loose dirt, as in BTW, instead of vanilla dirt.
- **`/gamerule doFireTick false` now also stops BTW's fire and lava fires.** Fire stays lit but does
  not spread or burn out, and lava sets nothing alight. With the rule on (the default), fire and lava
  behave exactly as in BTW. A burning ladder and a campfire can still spread fire.

Bone meal, newer plants and removed blocks (2026-10-15):

Three rulings by the maintainer bring more of Minecraft 1.20.1 in line with BTW 4.B. Each was tested in
a world on a dedicated server before and after the change.

- **Bone meal grew plants instantly.** On carrots, potatoes, wheat, stems, saplings, cocoa, beetroot or
  sweet berries it jumped them several stages, it grew mushrooms into huge mushrooms, and it was used
  up while the soil stayed as it was. In BTW bone meal never grows a plant: it fertilises BTW farmland
  and planters, which then grow crops twice as fast until the crop is fully grown. Bone meal now does
  exactly that, from either hand:
  - used on a plant, it fertilises the soil under the plant;
  - used on anything BTW cannot fertilise (grass blocks, netherrack, sand under water, a sapling on
    grass, ...), it does nothing and is kept: no grass, flowers, nylium or seagrass appear;
  - used on a crop whose soil has weeds, it pulls the weeds first and is kept, as in BTW;
  - it makes no particles or sound, as in BTW; the fertilised soil's look shows that it worked;
  - it no longer skips Minecraft's right-click checks, so protection mods can stop it.
- **White dye** no longer works as bone meal when you use it (dropped white dye still does; see Known
  issues).
- **Dispensers.** A vanilla dispenser now shoots bone meal out as an item, as in BTW, and the item
  fertilises the farmland it lands on. BTW's block dispenser now spits out items it has no use for
  (bone meal, dyes, string, ...), as in BTW; before, it clicked and kept them.
- **Weeds.** Right-clicking a crop, stem, sapling or flower whose soil has weeds pulls the weeds, as in
  BTW. Before, only BTW's own wheat did.
- **Bees** grew crops, stems and berry bushes a full stage per visit, ignoring BTW's growth rules. Each
  visit now gives the plant one extra growth chance under BTW's rules.
- **Plants added after 1.5.2 grew at 1.20.1's speed.** Each now follows the 4.B rules of its nearest
  BTW relative:
  - **Saplings.** Acacia, cherry and dark oak saplings and planted mangrove propagules grow like BTW's
    own saplings, about 18 times more slowly than in 1.20.1: they need light and never grow in the End.
    Dark oak needs four fully grown saplings in a square, like BTW's big jungle trees. Azaleas now grow
    into azalea trees by themselves, at the same pace.
  - **Crops.** Beetroot grows like BTW's carrots, and torchflower and pitcher crops like BTW's old
    wheat, at half the carrots' speed. They need light and watered soil, stop while weeds grow, grow
    twice as fast on fertilised soil and never grow in the End. Harvesting them turns BTW farmland back
    into loose dirt, as BTW's own crops do, and they still drop their own items.
  - **Nether fungi.** Crimson and warped fungi on their own nylium now grow into huge fungi by
    themselves, as BTW's mushrooms grow huge on mycelium, but never in the End.
  - **Other plants** keep their own growth but at the pace of their nearest BTW relative, and never in
    the End: bamboo and kelp grow like sugar cane, cave, weeping and twisting vines like BTW vines, and
    sweet berries and hanging mangrove propagules ripen like cocoa. Chorus flowers grow 16 times more
    slowly and still grow in the End, where they belong.
  - The leaves of the newer trees and nylium keep 1.20.1's decay, which is already as quick as BTW's.
- **Blocks and items that BTW 4.B removed came back.** The vanilla hopper, comparator, crafting table,
  chest, torch and others could be crafted again, found in chests and structures and picked from the
  creative tabs. They now stay removed (see Changed). The Minecraft recipes that 4.B removed or
  replaced with its own are gone too.

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
    starts the knitting, which you then finish by holding right-click (see the player report above).
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
- **Mechanical builds.** More blocks turn on turntables, all in 1.5.2's direction, and the redstone
  clutch, pulley, block dispenser and turntable ignore weak power, such as a redstone block or a
  powered block next to them (see Fixed). Contraptions built on the old behaviour may act
  differently.
- **Farming and forestry follow BTW 4.B** (see Fixed). Crops, saplings and other plants that keep a
  Minecraft id grow several times more slowly than in 1.20.1 (a sapling takes hours, not minutes),
  need light (crops also watered soil) and never grow in the End. Farms built around 1.20.1's rates
  produce much less. Leaves drop only saplings, and unripe crops drop nothing.
- **Bone meal is fertiliser, and newer plants grow at BTW's pace** (see Fixed). Farms that relied on
  bone meal, or on 1.20.1's speed for bamboo, kelp, sweet berries, beetroot or the newer trees, produce
  much less, and in the End only chorus grows. Bone meal still works the 1.20.1 way on blocks that
  BTW's rules do not cover, such as moss, nylium, pink petals, seagrass and sea pickles.
- **Blocks and items that BTW 4.B removed stay removed.**
  - **Removed:** the vanilla hopper, comparator, daylight detector, dropper, trapped chest, weighted
    pressure plates, activator rail, TNT and hopper minecarts and tripwire hook can no longer be
    crafted, are never found in loot and are not in the creative tabs or JEI, and string no longer
    places tripwire. BTW's own hopper, block dispenser, detector block and chest do their jobs, as in
    4.B. Structures place a 4.B block instead: a chest for the woodland mansion's trapped chest,
    repeaters for the ancient city's comparators, nothing for the jungle temple's tripwire trap. TNT
    and hopper minecarts that structures, spawn eggs, dispensers or commands create become plain
    minecarts.
  - **Replaced by BTW's own:** the vanilla crafting table, chest, ladder, torch, snow block, clay block
    and cobweb and the vanilla nether brick item can no longer be crafted and are not in the creative
    tabs. Loot gives the BTW version instead (an unlit finite torch, a block of BTW snow, BTW nether
    bricks from piglins, ...). The copies that structures place stay, and turn into the BTW item when
    broken, as in 4.B. Recipes that need one of them also accept the BTW item: the lantern takes a
    nether-coal torch, chest boats and the shulker box a BTW chest, terracotta BTW unfired clay, red
    nether bricks a BTW nether brick.
  - **Recipe removed:** wooden tools, the stone hoe, the snow layer and the furnace can no longer be
    crafted or found in loot, but stay in the creative tabs, as in 4.B.
  - Removed blocks that are already in a world, or placed with commands, are left alone. Operators can
    still `/give` and `/setblock` them, as in 4.B.
- **Minecraft recipes that BTW 4.B removed or replaced are gone.** All of 4.B's recipe removals now
  apply: 108 crafting recipes, 20 smelting, blasting, smoking and campfire recipes, extending maps and
  repairing two tools in the crafting grid. For example:
  - planks come from chopping logs with an axe or from the saw, bread from baking bread dough, stone
    axes, pickaxes and shovels from sharp stone, and diamond tools and armour from diamond ingots;
  - the enchanting table, the brewing stand and eyes of ender come only from BTW villagers;
  - ores, sand, cobblestone, logs and netherrack no longer cook in a furnace, blast furnace, smoker or
    campfire. BTW's kiln, crucible and other routes take over where 4.B has one.
- **BTW creative tab and JEI follow 4.B.** BTW items that 4.B kept out of its creative inventory,
  though they can be made in survival (Witch Wart, Mail, Steel Nugget, cut and tanned leather, Golden
  Dung and a few more), are no longer listed in the BTW tab, the Search tab or JEI's item list.
  Whether to list them anyway is the maintainer's decision.
- **Block item looks.** BTW blocks that have no item drawing of their own now show 1.5.2's plain cube
  item instead of the placed block's model, as in 4.B.
- **Network.** The server and every client must run the same build of this port (network protocol
  4, and the same set of BTW sound events). 1.3.0-alpha.3 clients and servers are refused.
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
- BTW's 1.5.2 sounds as 508 sound events, one per 1.5.2 sound name, playing the 1.5.2 sound files
  that Minecraft 1.20.1 still ships, with a 1.20.1 subtitle where one fits. They can be played with
  `/playsound`, for example `/playsound betterthanwolves:mob.zombiepig.zpig master @s`.
- Development tooling:
  - `tools/LinkAudit.java` checks the class precedence of the built jar and reports Minecraft class
    references in frozen BTW code;
  - a `jar` task guard fails the build on precedence drift;
  - `tools/BridgeAudit.java`'s texture check understands the `%blur%`/`%clamp%` prefixes and the
    entity renderer's vanilla fallbacks; its aux-effect check now follows BTW effects to BTW's own
    client handler and fails if that forwarding chain breaks;
  - `:Forge:generateLegacySoundsJson` (run by hand) builds `sounds.json` from the 1.5.2 and 1.20.1
    asset indexes, and `:Forge:generateBtwTextureAnimations` (part of every build) turns BTW's 1.5.2
    texture animation files into `.png.mcmeta` files.

### Known issues
- **Not checked on a client yet.** A dedicated server has been run on a fresh world three times
  (twice on 2026-10-10, once after the 2026-10-11 wave): it boots with no errors, BTW initialises
  and vanilla mobs are replaced. For the 2026-10-14 wave it ran three more times with scripted
  in-world tests of BTW's growth, fire and falling-block rules, and for the 2026-10-15 wave with
  scripted tests of bone meal, the newer plants and the removed blocks. The maintainer has played
  development builds, which is how the 2026-10-11 bugs were found, and the last session ran a build from
  the middle of that wave (its log shows the BTW tab built with 355 entries), but none of the in-game
  checks has been run on the final code. Every rendering fix (red tint, mob renderers and animations,
  held items, chests and baskets, block and item models, animated textures), every client-side sound,
  the creative tab and JEI are checked by code review only, and so is how BTW blocks draw their faces
  next to other blocks now that BTW's table of opaque blocks is filled (2026-10-14). So are the arm
  swing when bone meal is used, bone meal from the off hand, and the removed items' absence from the
  vanilla creative tabs and JEI (2026-10-15). Multiplayer is untested too.
- **Re-entering a world.** No cause was found. It has not been tested whether a world now always
  shuts down cleanly and releases its `session.lock`. If a world will not open again, please report
  it with `logs/latest.log` and any crash report.
- **BTW villagers and witches make no idle, hurt or death sounds** (witches still idle with BTW's
  quiet ghast scream). This is expected: it is how 4.B sounds, because Minecraft 1.5.2 had no
  villager or witch sound files. 1.3.0-alpha.3 played 1.20.1's witch hurt and death sounds.
- **The vanilla creative tabs still list a few Minecraft items that 4.B hid**, such as music discs
  other than "13", enchanted books and wheat seeds. Hiding them is waiting for the maintainer's
  decision (CT6). The blocks and items that 4.B removed or replaced (the vanilla hopper, comparator,
  chest, crafting table, cobweb, ladder, ...) are now hidden from the tabs (see Changed).
- **BTW's own placement code is skipped in some cases.** When you place a block item while sneaking,
  from the off hand, or against a block added after 1.5.2 (deepslate, tuff, ...) or a vanilla block
  with its own right-click action, Minecraft's placement runs with BTW's facing and variant, but
  without BTW's item-specific placement code: slabs do not join into a double slab, saplings ignore
  BTW's soil rules, a burning torch does not light the block you click, and BTW's "cannot be placed
  here" checks are not made (MISSED-1). Placing against any other block uses BTW's code.
- **Dropped white dye fertilises BTW farmland and planters** like bone meal, and is used up. Used by
  hand it does nothing. BTW reads 1.20.1's white dye as its own bone meal (see the dye note below), and
  the check that stops this for dropped items is still missing.
- **The blast furnace, smoker, furnace minecart and crossbow cannot be crafted from new materials**:
  their recipes need a furnace or a tripwire hook, which BTW 4.B removed. A furnace mined with Silk
  Touch (as in 4.B) or a hook from an older world still works in them. A BTW route for these recipes
  is planned.
- A tripwire hook given with `/give` cannot be placed. This is an older placement problem, not part of
  the removal of the hook.
- Dead bushes on grass blocks or dirt (1.20.1 places some in swamps and giant taigas) are removed when
  a block next to them changes, because BTW lets dead bushes grow only on sand. Dead bushes on sand,
  red sand and terracotta stay.
- Waterlogged blocks (stairs, slabs, fences, ...) do not count as water for BTW: sugar cane whose only
  water is in waterlogged blocks breaks when a block next to it changes, and BTW farmland watered only
  by them dries out.
- Huge mushrooms that grow from mushrooms on mycelium show placeholder textures. BTW's huge mushroom
  blocks already lacked their textures; before this release, mushrooms never grew into them.
- JEI's recipe and usage lookups (R and U) treat all variants of an item as one: looking up red knit
  wool shows the recipes of every knit wool colour (CI-3). The item list itself shows each variant.
- The BTW chest's item shows as a planks-textured cube, and a few other BTW block items whose 1.5.2
  item drawing is not ported yet show a plain cube.
- Chunk rebuilds in large BTW builds may be slower: many BTW building blocks (decorative blocks,
  slabs, sidings, mouldings, stairs) are now drawn from their live neighbours every time their chunk
  section is rebuilt. This has not been measured (RP-R2).
- Some mob animations and effects are still missing: squid tentacle movement, the cow's kick, the
  squid's tentacle attack, and villager and witch particles (R8).
- BTW's Hardcore Buoy option has no effect: dropped items float as in 1.20.1. The "Obstructed"
  player-names option hides only players' name tags behind walls, not named mobs'.
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
  - block-shaped items cooking on a campfire or in a brick oven are drawn turned a quarter turn;
  - BTW sounds pitched above 2.0 (the wet cement's moan, fizzing) are capped at 2.0 by 1.20.1;
  - some BTW sounds have a generic subtitle or none: hitting a BTW block reads "Footsteps", BTW's
    creaking machinery reads "Chest opens";
  - in water, soulforged boots do not change 1.20.1's swimming sounds.

## [1.3.0-alpha.3] - 2026-07-09

CurseForge: `BetterThanWolves-1.20.1-1.3.0-alpha.3.jar`, uploaded 2026-07-09. Git: `48e9825`
(version bump only); the jar was built from the tree at `b1ab3c7`, seven seconds before the bump
was committed. The version jumped from 0.4.0-alpha.3 (`2c806c8`, never uploaded) straight to
1.3.0-alpha.3; there are no 0.4-1.2 releases.

Everything since the 0.3.0-alpha.3 upload, including the 2026-06-05 re-audit:

### Fixed
- Bridge link crashes (including the soulforge crash), FC armor registering as real armor, and
  FC foods that could not be eaten or gave nutrition twice (`04274c9`).
- Entity registry crash on start-up (missing Hopper class) (`66b6fa0`).
- Movement modifiers applied twice ("super fast" walking) (`d4a6a98`) and sprint "flying"
  (`80d9a7c`); the movement penalty is now also applied on the client (`2c806c8`).
- FC container clicks needing two clicks, and dragging stacks across slots (`0db375a`).
- FC mobs rubber-banding (`5030245`).
- Blocks ignoring their orientation when placed, e.g. axles (`fe1dd22`).
- Spruce, birch and jungle logs, leaves, planks and slabs dropping as oak (`6b3417e`).
- Lightning rod collision box (`6451796`).
- Two systemic FC block render bugs: a GL11 matrix leak and invisible colour-multiplied
  geometry (`974c644`); stoked-fire textures (`a121a88`); soul urn textures (`532c521`,
  `ca0a5ed`).
- FC mobs dying at random from spurious suffocation (`581d5a2`, `253bca0`, `85fdd2d`, `b1ab3c7`).
  The last two also targeted the red tint, but they did not fix it (see Known issues and triage R4).

### Added
- FC melee damage modifier wired up (`532c521`).
- Renderers for XP orbs, arrows, snowballs, eggs, TNT and the fishing hook (`c2e15f3`, `c32cbf5`).
- Animated stoked-fire flame using FC's own procedural fire (`35b7885`, `0c55137`).
- Worldgen: FC lightning rod in jungle temples (`fc83664`); FC bonus basket replaces the
  bonus chest (`edf0e1b`, `0975006`, `d3072d8`).
- Village subsystem and many verified stub implementations (`a40ba58`, `f36f2d5`, `edd3e73`).
- Build/launch audit tooling (`8c8aea9`, `31ec424`).

### Known issues (found after release)
- Mobs and some textures turn dark red once an unowned XP orb has been drawn; the XP-orb
  renderer added in this release leaks its colour (triage R1).
- Every real hit on an FC mob is logged at WARN (`[MOB-DMG]` diagnostic left in).
- See `docs/curseforge-feedback-triage.md` for the full list.

## [0.3.0-alpha.3] - 2026-05-16

CurseForge upload of 2026-05-16. No commit matches it: the 0.3.0-alpha.3 label was only
committed on 2026-07-08 (`04274c9`), together with later work. The jar was built from an
uncommitted tree after `be8140d`; its exact contents are unknown. It does **not** contain the
2026-06-05 bridge fixes, the June armor registration, or any 2026-07-08/09 fix.

## [0.3.0-alpha.2] - 2026-04-21

CurseForge upload of 2026-04-21. Closest commit: `be8140d` (2026-04-20, "Patched some UI
elements"), which set this version.

## [0.2.0-alpha.2] - 2026-04-13

CurseForge upload of 2026-04-13. No commit ever carried this version; the jar was built from an
uncommitted tree between `9e9cb23` (2026-04-12, "Added entity movement") and `be8140d`.

## [0.1.0-alpha.1] - 2026-04-05

First CurseForge upload. The version label was only committed on 2026-04-12 (`9e9cb23`); the
jar was built from an uncommitted tree whose closest commit is `5257461` (2026-03-30). It is the
first public build of BTW 4.B0000003 running on the Forge 1.20.1 bridge (server and client), with
item textures and models, partial block models and UI, hunger and exhaustion, entity rendering
and the furnace brick (git, 2026-03-18 to 2026-03-30).
