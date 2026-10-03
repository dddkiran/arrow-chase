package com.arrowchase.game;

public sealed interface PlaceBetResult {

    String requestId();

    record Accepted(Bet bet, long balanceCents) implements PlaceBetResult {
        @Override
        public String requestId() {
            return bet.requestId();
        }
    }

    record Rejected(String requestId, RejectReason reason, long balanceCents) implements PlaceBetResult {
    }
}
