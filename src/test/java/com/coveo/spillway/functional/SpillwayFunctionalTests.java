package com.coveo.spillway.functional;

import static com.google.common.truth.Truth.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coveo.spillway.Spillway;
import com.coveo.spillway.SpillwayFactory;
import com.coveo.spillway.limit.Limit;
import com.coveo.spillway.limit.LimitBuilder;
import com.coveo.spillway.limit.LimitKey;
import com.coveo.spillway.storage.AsyncBatchLimitUsageStorage;
import com.coveo.spillway.storage.AsyncLimitUsageStorage;
import com.coveo.spillway.storage.InMemoryStorage;
import com.coveo.spillway.storage.RedisStorage;
import com.coveo.spillway.storage.RedisStorageTest;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Range;

import redis.clients.jedis.JedisPool;
import redis.embedded.RedisServer;

@Ignore("Functional tests, remove ignore to run them")
public class SpillwayFunctionalTests {

  private static final String RESOURCE1 = "someResource";
  private static final String LIMIT1 = "someLimit";
  private static final String PROPERTY1 = "someProperty";
  private static final Duration EXPIRATION = Duration.ofHours(1);
  private static final Instant TIMESTAMP = Instant.now();

  private static final int ONE_MILLION = 1000000;
  private static final double MARGIN_OF_ERROR = 0.00001;
  private static final String AN_IP = "127.0.0.1";

  private SpillwayFactory inMemoryFactory;

  private static final Logger logger = LoggerFactory.getLogger(RedisStorageTest.class);

  private static RedisServer redisServer;
  private static JedisPool jedis;
  private static RedisStorage storage;

  @SuppressWarnings("resource")
  @BeforeClass
  public static void startRedis() throws IOException {
    try {
      redisServer = new RedisServer(6389);
    } catch (IOException e) {
      logger.error("Failed to start Redis server. Is port 6389 available?");
      throw e;
    }
    redisServer.start();
    jedis = new JedisPool("localhost", 6389);
    storage = RedisStorage.builder().withJedisPool(new JedisPool("localhost", 6389)).build();
  }

  @AfterClass
  public static void stopRedis() {
    redisServer.stop();
  }

  @Before
  public void setup() {
    jedis.getResource().flushDB();
    inMemoryFactory = new SpillwayFactory(new InMemoryStorage());
  }

  @Test
  public void oneMillionConcurrentRequestsWith100Threads() throws Exception {
    Limit<String> ipLimit =
        LimitBuilder.of("perIp").to(ONE_MILLION).per(Duration.ofHours(1)).build();
    Spillway<String> spillway = inMemoryFactory.enforce("testResource", ipLimit);

    ExecutorService threadPool = Executors.newFixedThreadPool(100);

    AtomicInteger counter = new AtomicInteger(0);
    // We do 2 * ONE MILLION iterations and check to make sure that the counter was not incremented more than expected.
    for (int i = 0; i < 2 * ONE_MILLION; i++) {
      threadPool.submit(
          () -> {
            boolean canCall = spillway.tryCall(AN_IP);
            if (canCall) {
              counter.incrementAndGet();
            }
          });
    }
    threadPool.shutdown();
    threadPool.awaitTermination(1, TimeUnit.MINUTES);

    assertThat(counter.get()).isIn(inErrorMargin(ONE_MILLION, MARGIN_OF_ERROR));
  }

  @Test
  public void expiredKeysCompletelyDisappear() throws InterruptedException {
    int result1 =
        storage
            .incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, Duration.ofSeconds(1), TIMESTAMP)
            .getValue();

    assertThat(result1).isEqualTo(1);
    assertThat(storage.getCurrentLimitCounters()).hasSize(1);
    Thread.sleep(1000);
    assertThat(storage.getCurrentLimitCounters()).hasSize(1);
    Thread.sleep(2000);
    assertThat(storage.getCurrentLimitCounters()).hasSize(0);
  }

  @Test
  public void syncPerformance() {
    int numberOfCalls = 1000000;
    Pair<LimitKey, Integer> lastResponse = null;
    Stopwatch stopwatch = Stopwatch.createStarted();
    for (int i = 0; i < numberOfCalls; i++) {
      lastResponse =
          storage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP);
    }
    stopwatch.stop();
    long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);

    logger.info("Last response: {}", lastResponse);
    logger.info(
        "AddAndGet {} times took {} ms (average of {} ms per call)",
        numberOfCalls,
        elapsedMs,
        (float) elapsedMs / (float) numberOfCalls);
  }

  @Test
  public void asyncPerformance() throws Exception {
    AsyncLimitUsageStorage asyncStorage = new AsyncLimitUsageStorage(storage);
    int numberOfCalls = 1000000;
    Pair<LimitKey, Integer> lastResponse = null;
    Stopwatch stopwatch = Stopwatch.createStarted();
    for (int i = 0; i < numberOfCalls; i++) {
      lastResponse =
          asyncStorage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP);
    }
    asyncStorage.shutdownStorage();
    asyncStorage.awaitTermination(Duration.ofMinutes(1));
    stopwatch.stop();
    long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);

    logger.info("Last response: {}", lastResponse);
    logger.info(
        "AddAndGet {} times took {} ms (average of {} ms per call)",
        numberOfCalls,
        elapsedMs,
        (float) elapsedMs / (float) numberOfCalls);
  }

  @Test
  public void asyncBatchStorageTest() throws Exception {
    AsyncBatchLimitUsageStorage asyncStorage =
        new AsyncBatchLimitUsageStorage(storage, Duration.ofSeconds(5));
    int numberOfCalls = 1000000;
    for (int i = 0; i < numberOfCalls; i++) {
      asyncStorage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP);
    }

    Thread.sleep(5000);

    for (int i = 0; i < numberOfCalls; i++) {
      asyncStorage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP);
    }

    Map<LimitKey, Integer> currentCounters = asyncStorage.getCurrentLimitCounters();
    Map<LimitKey, Integer> cacheCounters = asyncStorage.debugCacheLimitCounters();

    currentCounters
        .entrySet()
        .forEach(
            entry -> {
              assertThat(entry.getValue()).isEqualTo(numberOfCalls);
            });

    cacheCounters
        .entrySet()
        .forEach(
            entry -> {
              assertThat(entry.getValue()).isEqualTo(2 * numberOfCalls);
            });
  }

  private Range<Integer> inErrorMargin(Integer expectedValue, double marginOfError) {
    marginOfError += 1;

    Integer lowerBound = (int) (expectedValue / marginOfError);
    Integer upperBound = (int) (expectedValue * marginOfError);

    return Range.closed(lowerBound, upperBound);
  }
}
