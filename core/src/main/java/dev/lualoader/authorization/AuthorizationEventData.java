package dev.lualoader.authorization;

import java.util.Objects;

/** Snapshot agnóstico de uma acção de jogador ainda não aplicada pelo jogo. */
public record AuthorizationEventData(
        String action,
        String dimension,
        int x,
        int y,
        int z,
        String targetId,
        String actorUuid,
        String actorName,
        String source,
        String face) {

    public AuthorizationEventData {
        if (!AuthorizationActions.isKnown(action)) {
            throw new IllegalArgumentException("acção de autorização desconhecida: " + action);
        }
        requireText(dimension, "dimension");
        if (targetId != null) requireText(targetId, "targetId");
        if (actorUuid != null) requireText(actorUuid, "actorUuid");
        if (actorName != null) requireText(actorName, "actorName");
        if (source != null) requireText(source, "source");
        if (face != null) requireText(face, "face");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " não pode ser nulo");
        if (value.isBlank()) throw new IllegalArgumentException(field + " não pode ser vazio");
    }

    public boolean hasActor() {
        return actorUuid != null;
    }
}
