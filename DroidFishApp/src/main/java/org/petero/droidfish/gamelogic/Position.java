/*
    DroidFish - An Android chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.petero.droidfish.gamelogic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stores the state of a chess position.
 * All required state is stored, except for all previous positions
 * since the last capture or pawn move. That state is only needed
 * for three-fold repetition draw detection, and is better stored
 * in a separate hash table.
 */
public class Position {
    private int[] squares;

    public boolean whiteMove;

    /** Bit definitions for the castleMask bit mask. */
    public static final int A1_CASTLE = 0; /** White long castle. */
    public static final int H1_CASTLE = 1; /** White short castle. */
    public static final int A8_CASTLE = 2; /** Black long castle. */
    public static final int H8_CASTLE = 3; /** Black short castle. */

    private int castleMask;
    private boolean chess960;
    private int whiteKingStart = 4, blackKingStart = 60;
    private int whiteQueenRookStart = 0, whiteKingRookStart = 7;
    private int blackQueenRookStart = 56, blackKingRookStart = 63;

    private int epSquare;

    /** Number of half-moves since last 50-move reset. */
    public int halfMoveClock;

    /** Game move number, starting from 1. */
    public int fullMoveCounter;

    private long hashKey;           // Cached Zobrist hash key
    private int wKingSq, bKingSq;   // Cached king positions

    /** Initialize board to empty position. */
    public Position() {
        squares = new int[64];
        for (int i = 0; i < 64; i++)
            squares[i] = Piece.EMPTY;
        whiteMove = true;
        castleMask = 0;
        chess960 = false;
        epSquare = -1;
        halfMoveClock = 0;
        fullMoveCounter = 1;
        hashKey = computeZobristHash();
        wKingSq = bKingSq = -1;
    }

    public Position(Position other) {
        squares = new int[64];
        System.arraycopy(other.squares, 0, squares, 0, 64);
        whiteMove = other.whiteMove;
        castleMask = other.castleMask;
        chess960 = other.chess960;
        whiteKingStart = other.whiteKingStart; blackKingStart = other.blackKingStart;
        whiteQueenRookStart = other.whiteQueenRookStart; whiteKingRookStart = other.whiteKingRookStart;
        blackQueenRookStart = other.blackQueenRookStart; blackKingRookStart = other.blackKingRookStart;
        epSquare = other.epSquare;
        halfMoveClock = other.halfMoveClock;
        fullMoveCounter = other.fullMoveCounter;
        hashKey = other.hashKey;
        wKingSq = other.wKingSq;
        bKingSq = other.bKingSq;
    }

    @Override
    public boolean equals(Object o) {
        if ((o == null) || (o.getClass() != this.getClass()))
            return false;
        Position other = (Position)o;
        if (!drawRuleEquals(other))
            return false;
        if (halfMoveClock != other.halfMoveClock)
            return false;
        if (fullMoveCounter != other.fullMoveCounter)
            return false;
        if (hashKey != other.hashKey)
            return false;
        return true;
    }
    @Override
    public int hashCode() {
        return (int)hashKey;
    }

    /**
     * Return Zobrish hash value for the current position.
     * Everything except the move counters are included in the hash value.
     */
    public final long zobristHash() {
        return hashKey;
    }

    /**
     * Decide if two positions are equal in the sense of the draw by repetition rule.
     * @return True if positions are equal, false otherwise.
     */
    final public boolean drawRuleEquals(Position other) {
        for (int i = 0; i < 64; i++) {
            if (squares[i] != other.squares[i])
                return false;
        }
        if (whiteMove != other.whiteMove)
            return false;
        if (castleMask != other.castleMask)
            return false;
        if (epSquare != other.epSquare)
            return false;
        if (chess960 != other.chess960 || whiteKingStart != other.whiteKingStart || blackKingStart != other.blackKingStart ||
                whiteQueenRookStart != other.whiteQueenRookStart || whiteKingRookStart != other.whiteKingRookStart ||
                blackQueenRookStart != other.blackQueenRookStart || blackKingRookStart != other.blackKingRookStart) return false;
        return true;
    }

    public final void setWhiteMove(boolean whiteMove) {
        if (whiteMove != this.whiteMove) {
            hashKey ^= whiteHashKey;
            this.whiteMove = whiteMove;
        }
    }
    /** Return index in squares[] vector corresponding to (x,y). */
    public static int getSquare(int x, int y) {
        return y * 8 + x;
    }
    /** Return x position (file) corresponding to a square. */
    public static int getX(int square) {
        return square & 7;
    }
    /** Return y position (rank) corresponding to a square. */
    public static int getY(int square) {
        return square >> 3;
    }
    /** Return true if (x,y) is a dark square. */
    public static boolean darkSquare(int x, int y) {
        return (x & 1) == (y & 1);
    }

    /** Return piece occupying a square. */
    public final int getPiece(int square) {
        return squares[square];
    }
    /** Set a square to a piece value. */
    public final void setPiece(int square, int piece) {
        // Update hash key
        int oldPiece = squares[square];
        hashKey ^= psHashKeys[oldPiece][square];
        hashKey ^= psHashKeys[piece][square];

        // Update board
        squares[square] = piece;

        // Update king position
        if (piece == Piece.WKING) {
            wKingSq = square;
        } else if (piece == Piece.BKING) {
            bKingSq = square;
        }
    }

    /** Return true if white long castling right has not been lost. */
    public final boolean a1Castle() {
        return (castleMask & (1 << A1_CASTLE)) != 0;
    }
    /** Return true if white short castling right has not been lost. */
    public final boolean h1Castle() {
        return (castleMask & (1 << H1_CASTLE)) != 0;
    }
    /** Return true if black long castling right has not been lost. */
    public final boolean a8Castle() {
        return (castleMask & (1 << A8_CASTLE)) != 0;
    }
    /** Return true if black short castling right has not been lost. */
    public final boolean h8Castle() {
        return (castleMask & (1 << H8_CASTLE)) != 0;
    }
    /** Bitmask describing castling rights. */
    public final int getCastleMask() {
        return castleMask;
    }

    public final boolean isChess960() { return chess960; }
    public final void setChess960(boolean enabled) {
        if (chess960 != enabled) { hashKey ^= chess960HashKey; chess960 = enabled; }
    }
    public final int getKingStartSquare(boolean white) { return white ? whiteKingStart : blackKingStart; }
    public final int getCastleRookStartSquare(boolean white, boolean kingSide) {
        if (white) return kingSide ? whiteKingRookStart : whiteQueenRookStart;
        return kingSide ? blackKingRookStart : blackQueenRookStart;
    }
    public final int getCastleKingTarget(boolean white, boolean kingSide) { return Position.getSquare(kingSide ? 6 : 2, white ? 0 : 7); }
    public final int getCastleRookTarget(boolean white, boolean kingSide) { return Position.getSquare(kingSide ? 5 : 3, white ? 0 : 7); }
    public final boolean hasCastleRight(boolean white, boolean kingSide) {
        int bit = white ? (kingSide ? H1_CASTLE : A1_CASTLE) : (kingSide ? H8_CASTLE : A8_CASTLE);
        return (castleMask & (1 << bit)) != 0;
    }
    public final void setCastleLayout(boolean frc, int whiteKing, int whiteQueenRook, int whiteKingRook,
                                      int blackKing, int blackQueenRook, int blackKingRook) {
        long before = extraCastleHash(castleMask);
        boolean variantChanged = chess960 != frc;
        whiteKingStart = whiteKing; whiteQueenRookStart = whiteQueenRook; whiteKingRookStart = whiteKingRook;
        blackKingStart = blackKing; blackQueenRookStart = blackQueenRook; blackKingRookStart = blackKingRook;
        hashKey ^= before ^ extraCastleHash(castleMask);
        if (variantChanged) hashKey ^= chess960HashKey;
        chess960 = frc;
    }
    public final boolean isCastleMove(Move move) {
        if (move == null) return false;
        boolean white = whiteMove;
        if (move.from != getKingStartSquare(white) || getPiece(move.from) != (white ? Piece.WKING : Piece.BKING)) return false;
        for (boolean kingSide : new boolean[]{true, false}) {
            if (!hasCastleRight(white, kingSide)) continue;
            int rookFrom = getCastleRookStartSquare(white, kingSide);
            int kingTo = getCastleKingTarget(white, kingSide);
            if (chess960 ? move.to == rookFrom : move.to == kingTo) return true;
        }
        return false;
    }
    public final boolean isCastleKingSide(Move move) {
        if (move == null) return false;
        boolean white = whiteMove;
        return hasCastleRight(white, true) && move.from == getKingStartSquare(white) &&
                (chess960 ? move.to == getCastleRookStartSquare(white, true) : move.to == getCastleKingTarget(white, true));
    }
    private long extraCastleHash(int mask) {
        long h = 0;
        if ((mask & (1 << A1_CASTLE)) != 0) h ^= castleRookHashKeys[0][whiteQueenRookStart] ^ kingStartHashKeys[0][whiteKingStart];
        if ((mask & (1 << H1_CASTLE)) != 0) h ^= castleRookHashKeys[1][whiteKingRookStart] ^ kingStartHashKeys[0][whiteKingStart];
        if ((mask & (1 << A8_CASTLE)) != 0) h ^= castleRookHashKeys[2][blackQueenRookStart] ^ kingStartHashKeys[1][blackKingStart];
        if ((mask & (1 << H8_CASTLE)) != 0) h ^= castleRookHashKeys[3][blackKingRookStart] ^ kingStartHashKeys[1][blackKingStart];
        return h;
    }
    final void saveCastleState(UndoInfo ui) {
        ui.chess960 = chess960; ui.whiteKingStart = whiteKingStart; ui.blackKingStart = blackKingStart;
        ui.whiteQueenRookStart = whiteQueenRookStart; ui.whiteKingRookStart = whiteKingRookStart;
        ui.blackQueenRookStart = blackQueenRookStart; ui.blackKingRookStart = blackKingRookStart;
    }
    final void restoreCastleState(UndoInfo ui) {
        setCastleLayout(ui.chess960, ui.whiteKingStart, ui.whiteQueenRookStart, ui.whiteKingRookStart,
                ui.blackKingStart, ui.blackQueenRookStart, ui.blackKingRookStart);
    }
    public final void setCastleMask(int castleMask) {
        hashKey ^= castleHashKeys[this.castleMask] ^ extraCastleHash(this.castleMask);
        hashKey ^= castleHashKeys[castleMask] ^ extraCastleHash(castleMask);
        this.castleMask = castleMask;
    }

    /** En passant square, or -1 if no ep possible. */
    public final int getEpSquare() {
        return epSquare;
    }
    public final void setEpSquare(int epSquare) {
        if (this.epSquare != epSquare) {
            hashKey ^= epHashKeys[(this.epSquare >= 0) ? getX(this.epSquare) + 1 : 0];
            hashKey ^= epHashKeys[(epSquare >= 0) ? getX(epSquare) + 1 : 0];
            this.epSquare = epSquare;
        }
    }


    public final int getKingSq(boolean whiteMove) {
        return whiteMove ? wKingSq : bKingSq;
    }

    /** Count number of pieces of a certain type. */
    public final int nPieces(int pType) {
        int ret = 0;
        for (int sq = 0; sq < 64; sq++)
            if (squares[sq] == pType)
                ret++;
        return ret;
    }

    /** Count total number of pieces. */
    public final int nPieces() {
        int ret = 0;
        for (int sq = 0; sq < 64; sq++)
            if (squares[sq] != Piece.EMPTY)
                ret++;
        return ret;
    }

    /** Apply a move to the current position. */
    public final void makeMove(Move move, UndoInfo ui) {
        ui.capturedPiece = squares[move.to];
        ui.castleMask = castleMask;
        ui.epSquare = epSquare;
        ui.halfMoveClock = halfMoveClock;
        saveCastleState(ui);
        ui.chess960Castle = false;
        boolean wtm = whiteMove;
        int p = squares[move.from];
        boolean nullMove = (move.from == 0) && (move.to == 0);
        boolean castle = !nullMove && isCastleMove(move);
        int capP = castle ? Piece.EMPTY : squares[move.to];
        if (nullMove || capP != Piece.EMPTY || p == (wtm ? Piece.WPAWN : Piece.BPAWN)) halfMoveClock = 0;
        else halfMoveClock++;
        if (!wtm) fullMoveCounter++;

        if (castle) {
            boolean kingSide = isCastleKingSide(move);
            int kingFrom = getKingStartSquare(wtm);
            int rookFrom = getCastleRookStartSquare(wtm, kingSide);
            int kingTo = getCastleKingTarget(wtm, kingSide);
            int rookTo = getCastleRookTarget(wtm, kingSide);
            ui.chess960Castle = true; ui.castleKingFrom = kingFrom; ui.castleRookFrom = rookFrom;
            ui.castleKingTo = kingTo; ui.castleRookTo = rookTo;
            int king = wtm ? Piece.WKING : Piece.BKING;
            int rook = wtm ? Piece.WROOK : Piece.BROOK;
            setEpSquare(-1);
            // Lift both pieces first; this also handles Chess960 overlap cases where
            // a king/rook begins on the other's final square.
            setPiece(kingFrom, Piece.EMPTY);
            setPiece(rookFrom, Piece.EMPTY);
            setPiece(kingTo, king);
            setPiece(rookTo, rook);
            int ownRights = wtm ? ((1 << A1_CASTLE) | (1 << H1_CASTLE)) : ((1 << A8_CASTLE) | (1 << H8_CASTLE));
            setCastleMask(castleMask & ~ownRights);
            setWhiteMove(!wtm);
            return;
        }

        int king = wtm ? Piece.WKING : Piece.BKING;
        if (p == king) {
            int ownRights = wtm ? ((1 << A1_CASTLE) | (1 << H1_CASTLE)) : ((1 << A8_CASTLE) | (1 << H8_CASTLE));
            setCastleMask(castleMask & ~ownRights);
        }
        if (!nullMove) {
            int rook = wtm ? Piece.WROOK : Piece.BROOK;
            if (p == rook) removeCastleRights(move.from);
            int oRook = wtm ? Piece.BROOK : Piece.WROOK;
            if (capP == oRook) removeCastleRights(move.to);
        }

        int prevEpSquare = epSquare;
        setEpSquare(-1);
        if (p == Piece.WPAWN) {
            if (move.to - move.from == 2 * 8) {
                int x = Position.getX(move.to);
                if (((x > 0) && (squares[move.to - 1] == Piece.BPAWN)) || ((x < 7) && (squares[move.to + 1] == Piece.BPAWN)))
                    setEpSquare(move.from + 8);
            } else if (move.to == prevEpSquare) setPiece(move.to - 8, Piece.EMPTY);
        } else if (p == Piece.BPAWN) {
            if (move.to - move.from == -2 * 8) {
                int x = Position.getX(move.to);
                if (((x > 0) && (squares[move.to - 1] == Piece.WPAWN)) || ((x < 7) && (squares[move.to + 1] == Piece.WPAWN)))
                    setEpSquare(move.from - 8);
            } else if (move.to == prevEpSquare) setPiece(move.to + 8, Piece.EMPTY);
        }
        setPiece(move.from, Piece.EMPTY);
        setPiece(move.to, move.promoteTo != Piece.EMPTY ? move.promoteTo : p);
        setWhiteMove(!wtm);
    }

    public final void unMakeMove(Move move, UndoInfo ui) {
        setWhiteMove(!whiteMove);
        boolean wtm = whiteMove;
        if (ui.chess960Castle) {
            int king = wtm ? Piece.WKING : Piece.BKING;
            int rook = wtm ? Piece.WROOK : Piece.BROOK;
            setPiece(ui.castleKingTo, Piece.EMPTY);
            setPiece(ui.castleRookTo, Piece.EMPTY);
            setPiece(ui.castleKingFrom, king);
            setPiece(ui.castleRookFrom, rook);
            setCastleMask(ui.castleMask);
            restoreCastleState(ui);
            setEpSquare(ui.epSquare);
            halfMoveClock = ui.halfMoveClock;
            if (!wtm) fullMoveCounter--;
            return;
        }
        int p = squares[move.to];
        setPiece(move.from, p);
        setPiece(move.to, ui.capturedPiece);
        setCastleMask(ui.castleMask);
        restoreCastleState(ui);
        setEpSquare(ui.epSquare);
        halfMoveClock = ui.halfMoveClock;
        if (move.promoteTo != Piece.EMPTY) setPiece(move.from, wtm ? Piece.WPAWN : Piece.BPAWN);
        if (!wtm) fullMoveCounter--;
        if (move.to == epSquare) {
            if (p == Piece.WPAWN) setPiece(move.to - 8, Piece.BPAWN);
            else if (p == Piece.BPAWN) setPiece(move.to + 8, Piece.WPAWN);
        }
    }

    private void removeCastleRights(int square) {
        int mask = castleMask;
        if (square == whiteQueenRookStart) mask &= ~(1 << Position.A1_CASTLE);
        if (square == whiteKingRookStart) mask &= ~(1 << Position.H1_CASTLE);
        if (square == blackQueenRookStart) mask &= ~(1 << Position.A8_CASTLE);
        if (square == blackKingRookStart) mask &= ~(1 << Position.H8_CASTLE);
        if (mask != castleMask) setCastleMask(mask);
    }

    /* ------------- Hashing code ------------------ */

    private static long[][] psHashKeys;    // [piece][square]
    private static long whiteHashKey;
    private static long[] castleHashKeys;  // [castleMask]
    private static long[][] castleRookHashKeys; // [right][rook origin square]
    private static long[][] kingStartHashKeys;  // [color][king origin square]
    private static long chess960HashKey;
    private static long[] epHashKeys;      // [epFile + 1] (epFile==-1 for no ep)

    static {
        psHashKeys = new long[Piece.nPieceTypes][64];
        castleHashKeys = new long[16];
        castleRookHashKeys = new long[4][64];
        kingStartHashKeys = new long[2][64];
        epHashKeys = new long[9];
        int rndNo = 0;
        for (int p = 0; p < Piece.nPieceTypes; p++) {
            for (int sq = 0; sq < 64; sq++) {
                psHashKeys[p][sq] = getRandomHashVal(rndNo++);
            }
        }
        whiteHashKey = getRandomHashVal(rndNo++);
        for (int cm = 0; cm < castleHashKeys.length; cm++)
            castleHashKeys[cm] = getRandomHashVal(rndNo++);
        for (int i = 0; i < 4; i++) for (int sq = 0; sq < 64; sq++) castleRookHashKeys[i][sq] = getRandomHashVal(rndNo++);
        for (int i = 0; i < 2; i++) for (int sq = 0; sq < 64; sq++) kingStartHashKeys[i][sq] = getRandomHashVal(rndNo++);
        chess960HashKey = getRandomHashVal(rndNo++);
        for (int f = 0; f < epHashKeys.length; f++)
            epHashKeys[f] = getRandomHashVal(rndNo++);
    }

    /**
     * Compute the Zobrist hash value non-incrementally. Only useful for test programs.
     */
    final long computeZobristHash() {
        long hash = 0;
        for (int sq = 0; sq < 64; sq++) {
            int p = squares[sq];
            hash ^= psHashKeys[p][sq];
        }
        if (whiteMove)
            hash ^= whiteHashKey;
        hash ^= castleHashKeys[castleMask] ^ extraCastleHash(castleMask);
        if (chess960) hash ^= chess960HashKey;
        hash ^= epHashKeys[(epSquare >= 0) ? getX(epSquare) + 1 : 0];
        return hash;
    }

    private static long getRandomHashVal(int rndNo) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] input = new byte[4];
            for (int i = 0; i < 4; i++)
                input[i] = (byte)((rndNo >> (i * 8)) & 0xff);
            byte[] digest = md.digest(input);
            long ret = 0;
            for (int i = 0; i < 8; i++) {
                ret ^= ((long)digest[i]) << (i * 8);
            }
            return ret;
        } catch (NoSuchAlgorithmException ex) {
            throw new UnsupportedOperationException("SHA-1 not available");
        }
    }

    /** Useful for debugging. */
    public final String toString() {
        return TextIO.asciiBoard(this);
    }
}
