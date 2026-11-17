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
        char ks = (char)('A' + kingRook);
        char qs = (char)('A' + queenRook);
        String rights = "" + ks + qs + Character.toLowerCase(ks) + Character.toLowerCase(qs);
        String upper = rank.toUpperCase(java.util.Locale.US);
        return rank + "/pppppppp/8/8/8/8/PPPPPPPP/" + upper + " w " + rights + " - 0 1";
    }
}
