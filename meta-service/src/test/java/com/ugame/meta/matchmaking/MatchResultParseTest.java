package com.ugame.meta.matchmaking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MatchResultParseTest {

    @Test
    void parsesResultLines() {
        var lines = MatchResultService.parse("1:0:3:1,2:1:1:3,broken");
        assertEquals(2, lines.size());
        assertEquals(1L, lines.get(0).playerId());
        assertEquals(1, lines.get(1).team());
        assertEquals(3, lines.get(1).deaths());
    }

    @Test
    void emptyResults() {
        assertEquals(0, MatchResultService.parse("").size());
        assertEquals(0, MatchResultService.parse(null).size());
    }
}
