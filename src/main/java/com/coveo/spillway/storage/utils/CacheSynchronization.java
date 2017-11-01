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
package com.coveo.spillway.storage.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map.Entry;
import java.util.TimerTask;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.coveo.spillway.limit.LimitKey;
import com.coveo.spillway.storage.AsyncBatchLimitUsageStorage;
import com.coveo.spillway.storage.InMemoryStorage;
import com.coveo.spillway.storage.LimitUsageStorage;

/**
 * Synchronization {@link TimerTask} that is launched periodically
 * by the {@link AsyncBatchLimitUsageStorage}.
 *
 * @author Emile Fugulin
 * @since 1.0.0
 */
public class CacheSynchronization extends TimerTask {
  private static final Logger logger = LoggerFactory.getLogger(CacheSynchronization.class);

  private InMemoryStorage cache;
  private LimitUsageStorage storage;

  public CacheSynchronization(InMemoryStorage cache, LimitUsageStorage storage) {
    this.cache = cache;
    this.storage = storage;
  }

  @Override
  public void run() {
    logger.info("Synchronizing spillway storage cache.");

    cache.applyOnEach(
        instantEntry -> {
          try {
            Instant expiration = instantEntry.getKey();

            instantEntry
                .getValue()
                .entrySet()
                .stream()
                .filter(valueEntry -> valueEntry.getKey().isDistributed())
                .forEach(valueEntry -> applyOnEachEntry(valueEntry, expiration));
          } catch (Exception e) {
            logger.warn("Exception during synchronization, ignoring.", e);
          }
        });
  }

  private void applyOnEachEntry(Entry<LimitKey, Capacity> entry, Instant expiration) {
    LimitKey limitKey = entry.getKey();

    int cost = entry.getValue().getDelta();

    Duration duration =
        Duration.ofMillis(expiration.toEpochMilli() - limitKey.getBucket().toEpochMilli());

    AddAndGetRequest request =
        new AddAndGetRequest.Builder()
            .withResource(limitKey.getResource())
            .withLimitName(limitKey.getLimitName())
            .withProperty(limitKey.getProperty())
            .withExpiration(duration)
            .withEventTimestamp(limitKey.getBucket())
            .withCost(cost)
            .build();

    Pair<LimitKey, Integer> reponse = storage.addAndGet(request);

    entry.getValue().substractAndGet(cost);
    entry.getValue().setTotal(reponse.getValue());
  }
}
