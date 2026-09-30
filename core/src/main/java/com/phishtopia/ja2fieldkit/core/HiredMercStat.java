package com.phishtopia.ja2fieldkit.core;

import com.phishtopia.ja2fieldkit.core.model.MercProfile;
import com.phishtopia.ja2fieldkit.core.model.LiveMercStats;

/** Closed, evidence-backed direct-set domain. See docs/hired-stat-edit-evidence.md. */
public enum HiredMercStat {
    AGILITY(1, 1, 100, 405, 880, 820),
    DEXTERITY(2, 1, 100, 335, 840, 821),
    STRENGTH(3, 1, 100, 296, 886, 822),
    LEADERSHIP(4, 1, 100, 341, 895, -1),
    WISDOM(5, 1, 100, 355, 841, 823),
    EXPERIENCE_LEVEL(6, 1, 10, 352, 849, -1),
    MARKSMANSHIP(7, 0, 100, 353, 1377, -1),
    MECHANICAL(8, 0, 100, 411, 916, -1),
    EXPLOSIVES(9, 0, 100, 339, 1378, -1),
    MEDICAL(10, 0, 100, 261, 1372, -1);

    private final int tag, minimum, maximum, profileOffset, soldierOffset, damageOffset;
    HiredMercStat(int tag, int minimum, int maximum, int profileOffset, int soldierOffset, int damageOffset) {
        this.tag = tag;
        this.minimum = minimum;
        this.maximum = maximum;
        this.profileOffset = profileOffset;
        this.soldierOffset = soldierOffset;
        this.damageOffset = damageOffset;
    }
    public int minimum() { return minimum; }
    public int maximum() { return maximum; }
    public boolean contains(int value) { return value >= minimum && value <= maximum; }
    int tag() { return tag; }
    int profileOffset() { return profileOffset; }
    int soldierOffset() { return soldierOffset; }
    // A nonzero injury counter can later restore points even when profile/live currently agree.
    boolean admitsInjuryState(byte[] soldier) { return damageOffset < 0 || soldier[damageOffset] == 0; }
    int profileValue(MercProfile stats) {
        return switch (this) {
            case AGILITY -> stats.getAgility();
            case DEXTERITY -> stats.getDexterity();
            case STRENGTH -> stats.getStrength();
            case LEADERSHIP -> stats.getLeadership();
            case WISDOM -> stats.getWisdom();
            case EXPERIENCE_LEVEL -> stats.getExperienceLevel();
            case MARKSMANSHIP -> stats.getMarksmanship();
            case MECHANICAL -> stats.getMechanical();
            case EXPLOSIVES -> stats.getExplosives();
            case MEDICAL -> stats.getMedical();
        };
    }
    int liveValue(LiveMercStats stats) {
        return switch (this) {
            case AGILITY -> stats.getAgility();
            case DEXTERITY -> stats.getDexterity();
            case STRENGTH -> stats.getStrength();
            case LEADERSHIP -> stats.getLeadership();
            case WISDOM -> stats.getWisdom();
            case EXPERIENCE_LEVEL -> stats.getExperienceLevel();
            case MARKSMANSHIP -> stats.getMarksmanship();
            case MECHANICAL -> stats.getMechanical();
            case EXPLOSIVES -> stats.getExplosives();
            case MEDICAL -> stats.getMedical();
        };
    }
}
