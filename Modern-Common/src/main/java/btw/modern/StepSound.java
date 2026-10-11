package btw.modern;

public class StepSound {

    public final String stepSoundName;
    public final float stepSoundVolume;
    public final float stepSoundPitch;

    public StepSound(String name, float volume, float pitch) {
        this.stepSoundName = name;
        this.stepSoundVolume = volume;
        this.stepSoundPitch = pitch;
    }

    public float getVolume() {
        return this.stepSoundVolume;
    }

    public float getPitch() {
        return this.stepSoundPitch;
    }

    /**
     * Used when a block breaks, EXA: Player break, Shep eating grass, etc..
     */
    public String getBreakSound() {
        return "dig." + this.stepSoundName;
    }

    /**
     * Used when a entity walks over, or otherwise interacts with the block.
     */
    public String getStepSound() {
        return "step." + this.stepSoundName;
    }

    public String getStepResourcePath() {
        return "step." + this.stepSoundName;
    }

    /**
     * Used when a player places a block.
     */
    public String getPlaceSound() {
        return getBreakSound();
    }

    // The three 1.5.2 StepSound subclasses behind Block.soundGlassFootstep / soundLadderFootstep /
    // soundAnvilFootstep (vanilla/client Block.java:37, 41-42). Without them glass-type blocks broke with
    // "dig.stone" instead of "random.glass", ladders broke with the nonexistent "dig.ladder", and anvils
    // broke / were placed with the nonexistent "dig.anvil" instead of "dig.stone" / "random.anvil_land".

    /** vanilla/client StepSoundStone.java: glass breaks with "random.glass" and is placed with "step.stone". */
    static final class StepSoundStone extends StepSound {
        StepSoundStone(String name, float volume, float pitch) {
            super(name, volume, pitch);
        }

        @Override
        public String getBreakSound() {
            return "random.glass";
        }

        @Override
        public String getPlaceSound() {
            return "step.stone";
        }
    }

    /** vanilla/client StepSoundSand.java: the ladder breaks (and is placed) with "dig.wood". */
    static final class StepSoundSand extends StepSound {
        StepSoundSand(String name, float volume, float pitch) {
            super(name, volume, pitch);
        }

        @Override
        public String getBreakSound() {
            return "dig.wood";
        }
    }

    /** vanilla/client StepSoundAnvil.java: the anvil breaks with "dig.stone" and lands with "random.anvil_land". */
    static final class StepSoundAnvil extends StepSound {
        StepSoundAnvil(String name, float volume, float pitch) {
            super(name, volume, pitch);
        }

        @Override
        public String getBreakSound() {
            return "dig.stone";
        }

        @Override
        public String getPlaceSound() {
            return "random.anvil_land";
        }
    }
}
