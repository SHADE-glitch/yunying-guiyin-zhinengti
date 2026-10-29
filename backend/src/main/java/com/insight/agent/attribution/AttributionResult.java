package com.insight.agent.attribution;

import java.util.List;

public record AttributionResult(
        AttributionOutcome outcome,
        List<AttributionNode> path,
        int probes,
        int invalidProposals,
        boolean dominantIsAggregate,
        String message,
        Evidence rootEvidence) {

    /** 无根证据的构造：单测里用。 */
    public AttributionResult(AttributionOutcome outcome, List<AttributionNode> path, int probes,
                             int invalidProposals, boolean dominantIsAggregate, String message) {
        this(outcome, path, probes, invalidProposals, dominantIsAggregate, message, null);
    }

    public boolean hasDominant() {
        return outcome == AttributionOutcome.DOMINANT_FOUND && !path.isEmpty();
    }

    public AttributionNode leaf() {
        return path.isEmpty() ? null : path.get(path.size() - 1);
    }
}
