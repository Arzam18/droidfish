package org.petero.droidfish.gamelogic;

/** Chess960 starting-position generator. IDs exposed to users are 1..960.
 * The ordering follows the ChessForAll reference implementation's Scharnagl tables.
 */
public final class Chess960Position {
    private Chess960Position() {}
    private static final String[] KING = {
        "qnnrkr", "nqnrkr", "nnqrkr", "nnrqkr", "nnrkqr", "nnrkrq",
        "qnrnkr", "nqrnkr", "nrqnkr", "nrnqkr", "nrnkqr", "nrnkrq",
        "qnrknr", "nqrknr", "nrqknr", "nrkqnr", "nrknqr", "nrknrq",
        "qnrkrn", "nqrkrn", "nrqkrn", "nrkqrn", "nrkrqn", "nrkrnq",
        "qrnnkr", "rqnnkr", "rnqnkr", "rnnqkr", "rnnkqr", "rnnkrq",
        "qrnknr", "rqnknr", "rnqknr", "rnkqnr", "rnknqr", "rnknrq",
        "qrnkrn", "rqnkrn", "rnqkrn", "rnkqrn", "rnkrqn", "rnkrnq",
        "qrknnr", "rqknnr", "rkqnnr", "rknqnr", "rknnqr", "rknnrq",
        "qrknrn", "rqknrn", "rkqnrn", "rknqrn", "rknrqn", "rknrnq",
        "qrkrnn", "rqkrnn", "rkqrnn", "rkrqnn", "rkrnqn", "rkrnnq"
    };
    private static final String[] BISHOP = {
        "bb------", "b--b----", "b----b--", "b------b", "-bb-----", "--bb----",
        "--b--b--", "--b----b", "-b--b---", "---bb---", "----bb--", "----b--b",
        "-b----b-", "---b--b-", "-----bb-", "------bb"
    };

    /** @param userId one-based Chess960 number, 1 through 960. */
    public static String fenForId(int userId) {
        return fenForIds(userId, userId);
    }

    /** Build a Double Fischer Random position from independent White/Black IDs. */
    public static String fenForIds(int whiteId, int blackId) {
        String whiteRank = backRankForId(whiteId);
        String blackRank = backRankForId(blackId);
        String rights = castleRights(whiteRank, true) + castleRights(blackRank, false);
        return blackRank + "/pppppppp/8/8/8/8/PPPPPPPP/" +
                whiteRank.toUpperCase(java.util.Locale.US) + " w " + rights + " - 0 1";
    }

    /** Lower-case back-rank arrangement for a one-based Chess960 ID. */
    public static String backRankForId(int userId) {
        if (userId < 1 || userId > 960) throw new IllegalArgumentException("Chess960 number must be 1..960");
        int id = userId - 1;
        String king = KING[id / 16];
        String bishops = BISHOP[id % 16];
        StringBuilder back = new StringBuilder(8);
        int ki = 0;
        for (int i = 0; i < 8; i++) back.append(bishops.charAt(i) == 'b' ? 'b' : king.charAt(ki++));
        String rank = back.toString();
        int kingFile = rank.indexOf('k');
        int queenRook = rank.indexOf('r');
        int kingRook = rank.lastIndexOf('r');
        if (kingFile < 0 || queenRook < 0 || kingRook < 0 || !(queenRook < kingFile && kingFile < kingRook))
            throw new IllegalStateException("Invalid Chess960 back rank for number " + userId);
        return rank;
    }

    private static String castleRights(String rank, boolean white) {
        int queenRook = rank.indexOf('r');
        int kingRook = rank.lastIndexOf('r');
        char k = (char)('a' + kingRook);
        char q = (char)('a' + queenRook);
        if (white) return (char)(k - 'a' + 'A') + "" + (char)(q - 'a' + 'A');
        return "" + k + q;
    }
}
