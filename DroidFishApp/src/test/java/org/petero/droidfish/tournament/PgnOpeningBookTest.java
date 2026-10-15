package org.petero.droidfish.tournament;

import org.junit.Test;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class PgnOpeningBookTest {
    @Test public void ignoresTagsCommentsVariationsAndResult() {
        String pgn = "[Event \"sample\"]\n1. e4 {main} e5 (1... c5) 2.Nf3 ;comment\nNc6 1/2-1/2";
        assertEquals(Arrays.asList("e4", "e5", "Nf3", "Nc6"), PgnOpeningBook.mainLineSan(pgn));
    }
    @Test public void choosesOneGameFromMultiGamePgn() {
        String pgn = "[Event \"one\"]\n1. e4 e5 1-0\n\n[Event \"two\"]\n1. d4 d5 0-1";
        String first = PgnOpeningBook.gameAt(pgn, 0);
        String second = PgnOpeningBook.gameAt(pgn, 1);
        String cycled = PgnOpeningBook.gameAt(pgn, 2);
        assertTrue(first.startsWith("[Event"));
        assertEquals(1, countGames(first));
        assertEquals(2, PgnOpeningBook.mainLineSan(first).size());
        assertEquals(Arrays.asList("d4", "d5"), PgnOpeningBook.mainLineSan(second));
        assertEquals(PgnOpeningBook.mainLineSan(first), PgnOpeningBook.mainLineSan(cycled));
    }
    private int countGames(String pgn) {
        Matcher matcher = Pattern.compile("(?m)^\\[Event\\s").matcher(pgn);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}
