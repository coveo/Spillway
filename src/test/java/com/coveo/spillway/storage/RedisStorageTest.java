/**
 * The MIT License
 * Copyright (c) 2016 Coveo
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.coveo.spillway.storage;

import static com.google.common.truth.Truth.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coveo.spillway.limit.LimitKey;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.embedded.RedisServer;

/**
 * These are slightly functional tests in the sense that they do not mock Redis.
 * The behavior of the RedisStorage class is tightly coupled with the behavior
 * of redis so it makes sense imho.
 */
public class RedisStorageTest {

  private static final String RESOURCE1 = "someResource";
  private static final String RESOURCE2 = "someOtherResource";
  private static final String LIMIT1 = "someLimit";
  private static final String LIMIT2 = "someOtherLimit";
  private static final String PROPERTY1 = "someProperty";
  private static final String PROPERTY2 = "someOtherProperty";
  private static final Duration EXPIRATION = Duration.ofHours(1);
  private static final Instant TIMESTAMP = Instant.now();

  private static final Logger logger = LoggerFactory.getLogger(RedisStorageTest.class);

  private static RedisServer redisServer;
  private static JedisPool jedis;
  private static RedisStorage storage;

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
  public void flushDataInRedis() {
    try (Jedis resource = jedis.getResource()) {
      resource.flushDB();
    }
  }

  @Test
  public void canIncrement() {
    int counter =
        storage
            .incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP)
            .getValue();
    assertThat(counter).isEqualTo(1);
  }

  @Test
  public void canIncrementMultipleTimes() {
    for (int i = 0; i < 10; i++) {
      int result =
          storage
              .incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP)
              .getValue();
      assertThat(result).isEqualTo(i + 1);
    }
  }

  @Test
  public void keysWithDifferentTimeStampGoInDifferentBuckets() {
    int result1 =
        storage
            .incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, Duration.ofSeconds(1), TIMESTAMP)
            .getValue();
    assertThat(result1).isEqualTo(1);

    int result2 =
        storage
            .incrementAndGet(
                RESOURCE1, LIMIT1, PROPERTY1, true, Duration.ofSeconds(1), TIMESTAMP.plusSeconds(1))
            .getValue();
    assertThat(result2).isEqualTo(1);
  }

  @Test
  public void canDebugLimitCounters() {
    for (int i = 0; i < 10; i++) {
      storage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1 + i, true, EXPIRATION, TIMESTAMP);
    }
    Map<LimitKey, Integer> limitCounters = storage.getCurrentLimitCounters();
    assertThat(limitCounters).hasSize(10);
    for (Map.Entry<LimitKey, Integer> limitCounter : limitCounters.entrySet()) {
      assertThat(limitCounter.getKey().getResource()).isEqualTo(RESOURCE1);
      assertThat(limitCounter.getKey().getProperty()).startsWith(PROPERTY1);
      assertThat(limitCounter.getKey().getBucket())
          .isGreaterThan(Instant.now().minus(EXPIRATION).minus(EXPIRATION));
      assertThat(limitCounter.getKey().getBucket()).isLessThan(Instant.now());
      assertThat(limitCounter.getValue()).isEqualTo(1);
    }
  }

  @Test
  public void canGetLimitsPerResource() {
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 5);
    storage.addAndGet(RESOURCE1, LIMIT2, PROPERTY1, true, EXPIRATION, TIMESTAMP, 10);
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY2, true, EXPIRATION, TIMESTAMP, 15);
    storage.addAndGet(RESOURCE2, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, -1);

    Map<LimitKey, Integer> result = storage.getCurrentLimitCounters(RESOURCE1);

    assertThat(result).hasSize(3);
    assertThat(result.containsValue(5));
    assertThat(result.containsValue(10));
    assertThat(result.containsValue(15));
  }

  @Test
  public void canGetLimitsPerResourceAndKey() {
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 5);
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY2, true, EXPIRATION, TIMESTAMP, 10);
    storage.addAndGet(RESOURCE1, LIMIT2, PROPERTY1, true, EXPIRATION, TIMESTAMP, -1);

    Map<LimitKey, Integer> result = storage.getCurrentLimitCounters(RESOURCE1, LIMIT1);

    assertThat(result).hasSize(2);
    assertThat(result.containsValue(5)).isTrue();
    assertThat(result.containsValue(10)).isTrue();
  }

  @Test
  public void canGetLimitsPerResourceKeyAndProperty() {
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 5);
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 10);
    storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY2, true, EXPIRATION, TIMESTAMP, -1);

    Map<LimitKey, Integer> result = storage.getCurrentLimitCounters(RESOURCE1, LIMIT1, PROPERTY1);

    assertThat(result).hasSize(1);
    assertThat(result.containsValue(15)).isTrue();
  }

  @Test
  public void canAddLargeValues() {
    int result =
        storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 5).getValue();
    assertThat(result).isEqualTo(5);
  }

  @Test
  public void canAddLargeValuesToExisitingCounters() {
    storage.incrementAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP);
    int result =
        storage.addAndGet(RESOURCE1, LIMIT1, PROPERTY1, true, EXPIRATION, TIMESTAMP, 5).getValue();

    assertThat(result).isEqualTo(6);
  }
}
