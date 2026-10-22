package org.petero.droidfish.book;

import java.util.ArrayList;
import java.util.Random;
import org.petero.droidfish.book.IOpeningBook.BookPosInput;
import org.petero.droidfish.book.DroidBook.BookEntry;
import org.petero.droidfish.gamelogic.Game;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.MoveGen;
import org.petero.droidfish.gamelogic.Position;

/** Tournament-only Polyglot BIN reader; does not alter DroidFish's global book settings. */
public final class TournamentBinBook {
    private final PolyglotBook book = new PolyglotBook();
    private final Random random = new Random();
    public TournamentBinBook(String path) { BookOptions o = new BookOptions(); o.filename = path == null ? "" : path; book.setOptions(o); }
    public Move choose(Game game) {
        if (game == null) return null;
        Position pos = game.currPos();
        ArrayList<BookEntry> entries = book.getBookEntries(new BookPosInput(game));
        if (entries == null || entries.isEmpty()) return null;
        ArrayList<Move> legal = new MoveGen().legalMoves(pos);
        long total = 0;
        for (BookEntry e : entries) { if (!legal.contains(e.move)) continue; total += Math.max(1, (long)e.weight); }
        if (total <= 0) return null;
        long pick = (long)(random.nextDouble() * total);
        for (BookEntry e : entries) { if (!legal.contains(e.move)) continue; pick -= Math.max(1, (long)e.weight); if (pick < 0) return e.move; }
        return null;
    }
}
