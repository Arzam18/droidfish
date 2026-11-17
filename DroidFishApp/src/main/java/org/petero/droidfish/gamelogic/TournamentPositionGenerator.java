package org.petero.droidfish.gamelogic;

/** Deterministic, duplicate-free position-pair sequencing for random-start tournaments. */
public final class TournamentPositionGenerator {
    private TournamentPositionGenerator() {}
    public static final class PositionPair {
        public final int whiteId;
        public final int blackId;
        public final String fen;
        PositionPair(int whiteId, int blackId, String fen) {
            this.whiteId = whiteId;
            this.blackId = blackId;
            this.fen = fen;
        }
    }

    /** One distinct pair from the 960 x 960 DFRC position space per sequence index. */
    public static PositionPair dfrc(long seed, int positionIndex) {
        int index = permutedIndex(seed, positionIndex, 960 * 960, 0x444652435F534545L);
        int white = index / 960 + 1;
        int black = index % 960 + 1;
        return new PositionPair(white, black, Chess960Position.fenForIds(white, black));
    }

    /** One distinct pair from the 18 x 18 Chess324 position space per sequence index. */
    public static PositionPair chess324(long seed, int positionIndex) {
        int count = Chess324Position.count();
        int index = permutedIndex(seed, positionIndex, count * count, 0x43483332345F5345L);
        int white = index / count + 1;
        int black = index % count + 1;
        return new PositionPair(white, black, Chess324Position.fenForIds(white, black));
    }

    /**
     * Seeded affine permutation over a finite domain. Its multiplier is coprime
     * to the domain, so every pair occurs exactly once before the sequence wraps.
     * This avoids storing a large shuffled position table in tournament JSON/RAM.
     */
    private static int permutedIndex(long seed, int positionIndex, int domain, long salt) {
        long mixedMultiplier = mix64(seed ^ salt ^ 0x9E3779B97F4A7C15L);
        int multiplier = positiveMod(mixedMultiplier, domain - 1) + 1;
        while (gcd(multiplier, domain) != 1) {
            multiplier++;
            if (multiplier >= domain) multiplier = 1;
        }
        int offset = positiveMod(mix64(seed ^ salt ^ 0xD1B54A32D192ED03L), domain);
        return positiveMod((long)multiplier * Math.max(0, positionIndex) + offset, domain);
    }

    /** Avoid Math.floorMod so the generator also runs on DroidFish's API 16 minimum. */
    private static int positiveMod(long value, int modulus) {
        long remainder = value % modulus;
        return (int)(remainder < 0 ? remainder + modulus : remainder);
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return Math.abs(a);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
