package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Kaufman Chess324 starting arrangements (18 choices independently per side). */
public final class Chess324Position {
    private Chess324Position() {}
    private static final List<String> BACK_RANKS = createBackRanks();

    private static List<String> createBackRanks() {
        ArrayList<String> ranks = new ArrayList<>(18);
        int[] darkBishopFiles = {2, 6};       // c/g on rank one
        int[] lightBishopFiles = {1, 3, 5};   // b/d/f on rank one
        for (int darkFile : darkBishopFiles) {
            for (int lightFile : lightBishopFiles) {
                for (int queenSlot = 0; queenSlot < 3; queenSlot++) {
                    char[] rank = new char[]{'r', '.', '.', '.', 'k', '.', '.', 'r'};
                    rank[darkFile] = 'b';
                    rank[lightFile] = 'b';
                    int[] remaining = new int[3];
                    int next = 0;
                    for (int file = 1; file < 7; file++)
                        if (rank[file] == '.') remaining[next++] = file;
                    rank[remaining[queenSlot]] = 'q';
                    for (int i = 0; i < remaining.length; i++)
                        if (i != queenSlot) rank[remaining[i]] = 'n';
                    ranks.add(new String(rank));
                }
            }
        }
        if (ranks.size() != 18 || new java.util.HashSet<>(ranks).size() != 18)
            throw new IllegalStateException("Chess324 generator did not produce 18 unique back ranks");
        return Collections.unmodifiableList(ranks);
    }

    /** One-based ID, 1..18. */
    public static String backRankForId(int id) {
        if (id < 1 || id > 18) throw new IllegalArgumentException("Chess324 side ID must be 1..18");
        return BACK_RANKS.get(id - 1);
    }

    /** White and Black arrangements are selected independently. */
    public static String fenForIds(int whiteId, int blackId) {
        String white = backRankForId(whiteId);
        String black = backRankForId(blackId);
        // The king remains on e-file and rooks remain on a/h, so orthodox KQkq
        // rights and standard castling moves are correct for every combination.
        return black + "/pppppppp/8/8/8/8/PPPPPPPP/" +
                white.toUpperCase(Locale.US) + " w KQkq - 0 1";
    }

    public static int count() { return BACK_RANKS.size(); }
}
