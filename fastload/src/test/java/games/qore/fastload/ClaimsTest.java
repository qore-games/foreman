package games.qore.fastload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsTest {
    @Test
    void firstClaimWins() {
        String feature = "test-first";
        assertNull(Claims.owner(feature));
        assertTrue(Claims.claim(feature, "a"));
        assertFalse(Claims.claim(feature, "b"));
        assertTrue(Claims.claim(feature, "a"));
        assertEquals("a", Claims.owner(feature));
        assertEquals("a", System.getProperty("fastload.claim." + feature));
    }

    @Test
    void oneWinnerAcrossThreads() throws InterruptedException {
        String feature = "test-threads";
        AtomicInteger winners = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String owner = "owner" + i;
            threads.add(Thread.ofPlatform().start(() -> {
                if (Claims.claim(feature, owner)) winners.incrementAndGet();
            }));
        }
        for (Thread thread : threads) thread.join();
        assertEquals(1, winners.get());
    }
}
