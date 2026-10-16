package org.petero.droidfish.tournament;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the main-line SAN sequence from a PGN game, ignoring comments/variations. */
public final class PgnOpeningBook {
    private static final Pattern MOVE_NUMBER = Pattern.compile("^\\d+\\.{1,3}$");
    private static final Pattern RESULT = Pattern.compile("^(1-0|0-1|1/2-1/2|\\*)$");

    private PgnOpeningBook() {}

    public static List<String> mainLineSan(String pgn) {
        if (pgn == null || pgn.isEmpty()) return Collections.emptyList();
        StringBuilder clean = new StringBuilder(pgn.length());
        boolean inTag = false, inBrace = false, inLineComment = false;
        int variationDepth = 0;
        for (int i = 0; i < pgn.length(); i++) {
            char c = pgn.charAt(i);
            if (inLineComment) { if (c == '\n' || c == '\r') { inLineComment = false; clean.append(' '); } continue; }
            if (inBrace) { if (c == '}') inBrace = false; continue; }
            if (inTag) { if (c == ']') inTag = false; continue; }
            if (c == '{') { inBrace = true; continue; }
            if (c == '[') { inTag = true; continue; }
            if (c == ';') { inLineComment = true; continue; }
            if (c == '(') { variationDepth++; continue; }
            if (c == ')' && variationDepth > 0) { variationDepth--; continue; }
            if (variationDepth == 0) clean.append(c); else clean.append(' ');
        }
        List<String> moves = new ArrayList<>();
        for (String token : clean.toString().trim().split("\\s+")) {
            if (token.isEmpty() || MOVE_NUMBER.matcher(token).matches()) continue;
            if (RESULT.matcher(token).matches()) break; // One PGN game defines one opening line.
            // Ignore NAGs and annotation glyphs, and handle compact tokens such as 12...Nf6.
            String san = token.replaceFirst("^\\d+\\.(?:\\.\\.)?", "")
                              .replaceFirst("\\$\\d+$", "")
                              .replaceAll("[!?]+$", "");
            if (!san.isEmpty() && !RESULT.matcher(san).matches() && !san.equals("e.p.")) moves.add(san);
        }
        return moves;
    }

    /** Read a PGN tag value, returning an empty string when the tag is absent. */
    public static String tagValue(String pgn, String tagName) {
        if (pgn == null || tagName == null) return "";
        Pattern pattern = Pattern.compile("(?m)^\\[" + Pattern.quote(tagName) + "\\s+\"([^\"]*)\"\\]");
        Matcher matcher = pattern.matcher(pgn);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** Select one PGN game in order, cycling to the first opening when the list ends. */
    public static String gameAt(String pgn, int index) {
        if (pgn == null || pgn.trim().isEmpty()) return "";
        String[] games = pgn.split("(?m)(?=^\\[Event\\s)");
        List<String> nonEmpty = new ArrayList<>();
        for (String game : games) if (!game.trim().isEmpty()) nonEmpty.add(game.trim());
        if (nonEmpty.isEmpty()) return pgn;
        int selected = index % nonEmpty.size();
        if (selected < 0) selected += nonEmpty.size();
        return nonEmpty.get(selected);
    }

    /** Callback used to apply a parsed SAN token to the active chess position. */
    public interface SanMoveApplier { boolean applySan(String san); }

    /** Apply up to maxPlies main-line SAN moves; stops at the first illegal token. */
    public static int applyMainLine(String pgn, int maxPlies, SanMoveApplier applier) {
        if (applier == null || maxPlies <= 0) return 0;
        int applied = 0;
        for (String san : mainLineSan(pgn)) {
            if (applied >= maxPlies || !applier.applySan(san)) break;
            applied++;
        }
        return applied;
    }
}
