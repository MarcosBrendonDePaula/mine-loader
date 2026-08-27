package dev.lualoader.authorization;

import java.util.Set;

/** Vocabulário versionado de acções que podem ser autorizadas antes da mutação. */
public final class AuthorizationActions {
    public static final String CAPABILITY = "events.action.authorization";
    public static final String BLOCK_BREAK = "block.break";
    public static final String BLOCK_PLACE = "block.place";
    public static final String BLOCK_USE = "block.use";

    public static final Set<String> ALL = Set.of(BLOCK_BREAK, BLOCK_PLACE, BLOCK_USE);

    private AuthorizationActions() {
    }

    public static boolean isKnown(String action) {
        return ALL.contains(action);
    }
}
