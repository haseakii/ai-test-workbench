package io.aitest.workbench.infrastructure;

import java.util.UUID;

/** Server-chosen authorization target, never supplied by a client or inferred from a response. */
public record CommandScope(Kind kind, UUID objectId) {
    public enum Kind { SUBJECT, ADMIN, ENVIRONMENT, CONVERSATION, APPROVAL, ATTACHMENT, ARTIFACT, CONTENT }
    public static CommandScope admin(){return new CommandScope(Kind.ADMIN,null);}
    public static CommandScope subject(){return new CommandScope(Kind.SUBJECT,null);}
    public static CommandScope of(Kind kind,UUID id){return new CommandScope(kind,id);}
}
