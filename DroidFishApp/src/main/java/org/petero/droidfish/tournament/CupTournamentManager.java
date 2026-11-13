package org.petero.droidfish.tournament;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Cup-only bracket rules. The shared TournamentManager still owns persistence and
 * scheduled game records; this class contains the deterministic bracket/seeding rules.
 */
public final class CupTournamentManager {
    public enum SeedingMode { RANDOM, RANK_ENGINE }

    public static final int MAX_ENGINES = 512;
    private CupTournamentManager() { }

    public static int bracketSizeFor(int entrants) {
        if (entrants < 2) throw new IllegalArgumentException("A Cup requires at least two engines");
        if (entrants > MAX_ENGINES) throw new IllegalArgumentException("Cup supports up to " + MAX_ENGINES + " engines");
        int size = 2;
        while (size < entrants) size <<= 1;
        return size;
    }

    public static int stageCount(int bracketSize) {
        if (bracketSize < 2 || (bracketSize & (bracketSize - 1)) != 0)
            throw new IllegalArgumentException("Cup bracket size must be a power of two");
        int count = 0;
        while (bracketSize > 1) { bracketSize >>= 1; count++; }
        return count;
    }

    /**
     * Returns seed numbers in the display/play order used by the TCEC-style bracket.
     * For 32 entrants this begins 1,17,9,25,5,21,13,29,... and therefore produces
     * first-round matches 1-vs-17, 9-vs-25, 5-vs-21, 13-vs-29, etc.
     */
    public static List<Integer> firstRoundSeedOrder(int bracketSize) {
        stageCount(bracketSize); // validate
        ArrayList<Integer> order = new ArrayList<>();
        order.add(1);
        for (int size = 2; size <= bracketSize; size <<= 1) {
            int half = size >> 1;
            ArrayList<Integer> expanded = new ArrayList<>(size);
            for (Integer seed : order) {
                expanded.add(seed);
                expanded.add(seed + half);
            }
            order = expanded;
        }
        return Collections.unmodifiableList(order);
    }

    /**
     * Constructs one immutable seed-slot draw. Rank mode preserves the top eight
     * ranks in equal-distance bracket seeds and randomizes the remaining entrants;
     * Random mode randomizes the complete field. Nulls are first-round byes.
     */
    public static List<String> createSeedSlots(List<String> selectedIds, List<String> rankOrder,
                                                SeedingMode mode, long randomSeed, int bracketSize) {
        if (selectedIds == null || selectedIds.size() < 2)
            throw new IllegalArgumentException("A Cup requires at least two engines");
        if (bracketSize != bracketSizeFor(selectedIds.size()))
            throw new IllegalArgumentException("Invalid Cup bracket size");
        if (new java.util.HashSet<>(selectedIds).size() != selectedIds.size() || selectedIds.contains(null))
            throw new IllegalArgumentException("Cup entrants contain a missing or duplicated engine");

        ArrayList<String> entrants = new ArrayList<>(selectedIds);
        if (mode == SeedingMode.RANK_ENGINE) {
            if (rankOrder == null || rankOrder.size() != selectedIds.size())
                throw new IllegalArgumentException("Rank Engine mode requires a complete ranking of every selected engine");
            java.util.HashSet<String> selected = new java.util.HashSet<>(selectedIds);
            java.util.HashSet<String> ranked = new java.util.HashSet<>();
            for (String id : rankOrder) {
                if (id == null || !selected.contains(id) || !ranked.add(id))
                    throw new IllegalArgumentException("Cup ranking contains a missing, unknown, or duplicated engine");
            }
            if (ranked.size() != selected.size())
                throw new IllegalArgumentException("Cup ranking is incomplete");
            entrants = new ArrayList<>(rankOrder);
        }

        Random random = new Random(randomSeed);
        ArrayList<String> slots = new ArrayList<>(Collections.nCopies(bracketSize, (String)null));
        List<Integer> order = firstRoundSeedOrder(bracketSize);
        int matchCount = bracketSize / 2;
        int byeCount = bracketSize - entrants.size();

        if (mode == SeedingMode.RANK_ENGINE) {
            // Seed numbers 1..8 remain fixed and are spread through the bracket by
            // firstRoundSeedOrder(). For a partial field, the highest byeCount seeds
            // receive byes. Their opposite slots stay empty; all remaining entrants
            // are randomly drawn into the remaining live slots. This avoids the bad
            // 33-player case where seed #1 would otherwise be forced to play #33.
            int fixedCount = byeCount == 0 ? Math.min(8, entrants.size())
                    : Math.max(byeCount, Math.min(8, Math.min(entrants.size(), bracketSize / 2)));
            java.util.HashSet<Integer> reservedMateSlots = new java.util.HashSet<>();
            for (int seed = 1; seed <= byeCount; seed++) {
                int position = order.indexOf(seed);
                if (position < 0) throw new IllegalStateException("Invalid Cup seed layout");
                int mateSeed = order.get(position ^ 1);
                reservedMateSlots.add(mateSeed);
            }
            for (int seed = 1; seed <= fixedCount; seed++) {
                if (reservedMateSlots.contains(seed))
                    throw new IllegalStateException("Cup bye seeding overlaps a protected seed");
                slots.set(seed - 1, entrants.get(seed - 1));
            }
            ArrayList<Integer> freeSlots = new ArrayList<>();
            for (int seed = 1; seed <= bracketSize; seed++) {
                if (slots.get(seed - 1) == null && !reservedMateSlots.contains(seed)) freeSlots.add(seed - 1);
            }
            ArrayList<String> remaining = new ArrayList<>(entrants.subList(fixedCount, entrants.size()));
            Collections.shuffle(remaining, random);
            if (remaining.size() != freeSlots.size())
                throw new IllegalStateException("Cup seed and bye allocation did not balance");
            for (int i = 0; i < remaining.size(); i++) slots.set(freeSlots.get(i), remaining.get(i));
        } else {
            // Independent Cup draw: randomize participants, bye recipients, bracket
            // positions, and color-side positions while retaining the immutable draw.
            Collections.shuffle(entrants, random);
            ArrayList<Integer> matchPositions = new ArrayList<>();
            for (int i = 0; i < matchCount; i++) matchPositions.add(i);
            Collections.shuffle(matchPositions, random);
            for (int i = 0; i < byeCount; i++) {
                int matchIndex = matchPositions.get(i);
                int firstSlot = order.get(matchIndex * 2) - 1;
                int secondSlot = order.get(matchIndex * 2 + 1) - 1;
                int targetSlot = random.nextBoolean() ? firstSlot : secondSlot;
                slots.set(targetSlot, entrants.get(i));
            }
            int entrantIndex = byeCount;
            for (int i = byeCount; i < matchCount; i++) {
                int matchIndex = matchPositions.get(i);
                int firstSlot = order.get(matchIndex * 2) - 1;
                int secondSlot = order.get(matchIndex * 2 + 1) - 1;
                slots.set(firstSlot, entrants.get(entrantIndex++));
                slots.set(secondSlot, entrants.get(entrantIndex++));
            }
        }

        java.util.HashSet<String> assigned = new java.util.HashSet<>();
        for (String id : slots) if (id != null && !assigned.add(id))
            throw new IllegalStateException("Cup seeding assigned an engine more than once");
        if (assigned.size() != selectedIds.size())
            throw new IllegalStateException("Cup seeding did not place every selected engine");
        return Collections.unmodifiableList(slots);
    }

    public static String stageLabel(int bracketSize, int stageIndex) {
        int stages = stageCount(bracketSize);
        if (stageIndex < 0 || stageIndex >= stages) throw new IllegalArgumentException("Invalid Cup stage");
        int remaining = stages - stageIndex;
        if (remaining == 1) return "Final";
        if (remaining == 2) return "Semi-final";
        if (remaining == 3) return "Quarter-final";
        return "Round " + (bracketSize >> stageIndex);
    }

    /** Regular two-game color-reversed pairs before a tied match enters tiebreak. */
    public static int regularPairsPerMatch(int bracketSize, int stageIndex) {
        int remaining = stageCount(bracketSize) - stageIndex - 1;
        if (remaining >= 4) return 2;  // Round 64/32 and earlier play-in stages
        if (remaining == 3) return 3; // Round of 16
        if (remaining == 2) return 4; // Quarter-finals
        if (remaining == 1) return 6; // Semi-finals
        return 12;                    // Final
    }
}
