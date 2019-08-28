package com.stolsvik.mats.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.MatsBasicTest;
import com.stolsvik.mats.util.MatsFuturizer.Reply;

/**
 * Basic tests of the MatsFuturizer.
 *
 * @author Endre Stølsvik 2019-08-28 00:22 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_BasicMatsFuturizer extends MatsBasicTest {
    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, msg) -> {
                    log.info("Inside SERVICE, context:\n" + context);
                    if (msg == null) {
                        return null;
                    }
                    return new DataTO(msg.number * 2, msg.string + ":FromService");
                });
    }

    @Test
    public void normalMessage() throws ExecutionException, InterruptedException, TimeoutException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!! ==
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(matsRule.getMatsFactory(),
                this.getClass().getSimpleName())) {

            DataTO dto = new DataTO(42, "TheAnswer");
            CompletableFuture<Reply<DataTO>> future = futurizer.futurizeInteractiveUnreliable(
                    "traceId", "http://example.com/testing1234?abc=123", SERVICE, 5000, DataTO.class, dto);

            Reply<DataTO> result = future.get(1, TimeUnit.SECONDS);

            Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.reply);

            log.info("Got the reply from the Future - the latency was " + (System.currentTimeMillis()
                    - result.initiatedTimestamp) + " milliseconds");
        }
        Thread.sleep(200);
    }

    @Test
    public void manyMessages() throws ExecutionException, InterruptedException, TimeoutException {
        // =============================================================================================
        // == NOTE: Using try-with-resources in this test - NOT TO BE USED IN NORMAL CIRCUMSTANCES!!! ==
        // =============================================================================================
        try (MatsFuturizer futurizer = MatsFuturizer.createMatsFuturizer(matsRule.getMatsFactory(),
                this.getClass().getSimpleName())) {

            // Warm-up:
            runTest(futurizer, 50); // 1000 msgs -> 1197.108 ms total -> 1.197 ms per message

            // Timed run:
            runTest(futurizer, 50); // 1000 msgs -> 679.391 ms total -> 0.679 ms per message
        }
    }

    private void runTest(MatsFuturizer futurizer, int number) throws InterruptedException, ExecutionException,
            TimeoutException {
        // :: Send a bunch of messages
        long startNanos = System.nanoTime();
        List<CompletableFuture<Reply<DataTO>>> futures = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            DataTO dto = new DataTO(i, "TheAnswer");

            futures.add(futurizer.futurizeInteractiveUnreliable(
                    "traceId", "http://example.com/testing1234?abc=123", SERVICE, 5000, DataTO.class, dto));
        }

        // :: Wait for each of them to complete
        for (int i = 0; i < number; i++) {
            Reply<DataTO> result = futures.get(i).get(60, TimeUnit.SECONDS);
            Assert.assertEquals(new DataTO(i * 2, "TheAnswer:FromService"), result.reply);
        }
        double totalTimeMs = (System.nanoTime() - startNanos) / 1_000_000d;
        log.info("#TIMED# Got the reply from all [" + number + "] Futures - total time:[" + (totalTimeMs)
                + " ms] , per message:[" + (totalTimeMs / number) + " ms]");
    }

}
