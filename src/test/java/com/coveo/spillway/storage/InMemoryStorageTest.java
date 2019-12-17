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

import com.coveo.spillway.storage.utils.AddAndGetRequest;
import com.google.common.collect.Lists;
import java.util.stream.IntStream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.coveo.spillway.limit.LimitKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class InMemoryStorageTest {

  private static final String RESOURCE1 = "someResource";
  private static final String RESOURCE2 = "someOtherResource";
  private static final String LIMIT1 = "someLimit";
  private static final String LIMIT2 = "someOtherLimit";
  private static final String PROPERTY1 = "someProperty";
  private static final String PROPERTY2 = "someOtherProperty";
  private static final Duration EXPIRATION = Duration.ofHours(1);
  private static final Instant TIMESTAMP = Instant.now();

  @Mock private Clock clock;

  @InjectMocks private InMemoryStorage storage;

  @Before
  public void setup() {
    when(clock.instant()).thenReturn(Instant.now());
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
  public void expiredEntriesAreRemovedFromDebugInfo() {
    storage.incrementAndGet(
        RESOURCE1, LIMIT1, PROPERTY1, true, Duration.ofSeconds(2), Instant.now());
    assertThat(storage.getCurrentLimitCounters()).hasSize(1);
    assertThat(storage.getCurrentLimitCounters()).hasSize(1);

    // Fake sleep two seconds to ensure that we bump to another bucket
    when(clock.instant()).thenReturn(Instant.now().plusSeconds(4));

    assertThat(storage.getCurrentLimitCounters()).isEmpty();
  }

  @Test
  public void calculateAndVerifySlidingWindowCount() {
    when(clock.instant()).thenReturn(Instant.parse("2018-11-30T18:35:00.00Z"));
    AddAndGetRequest.Builder addAndGetRequestBuilder =
        new AddAndGetRequest.Builder()
            .withCost(1)
            .withEventTimestamp(Instant.parse("2018-11-30T18:35:26.00Z"))
            .withExpiration(Duration.ofMinutes(1))
            .withLimit(1000);
    IntStream.range(0, 10)
        .forEach(
            i -> {
              AddAndGetRequest addAndGetRequest =
                  addAndGetRequestBuilder
                      .withEventTimestamp(Instant.parse("2018-11-30T18:35:26.00Z"))
                      .build();
              storage.addAndGetWithLimit(Lists.newArrayList(addAndGetRequest));
            });
    when(clock.instant()).thenReturn(Instant.parse("2018-11-30T18:36:00.00Z"));

    AddAndGetRequest addAndGetRequest =
        addAndGetRequestBuilder
            .withEventTimestamp(Instant.parse("2018-11-30T18:36:06.00Z"))
            .build();
    Assert.assertEquals(addAndGetRequest.getPreviousBucketCounterPercentage(), 0.9, 0.00001);
    assertThat(
            storage
                .addAndGetWithLimit(Lists.newArrayList(addAndGetRequest))
                .get(LimitKey.fromRequest(addAndGetRequest)))
        .isEqualTo(9);

    addAndGetRequest =
        addAndGetRequestBuilder
            .withEventTimestamp(Instant.parse("2018-11-30T18:36:15.00Z"))
            .build();
    Assert.assertEquals(addAndGetRequest.getPreviousBucketCounterPercentage(), 0.75, 0.00001);
    assertThat(
            storage
                .addAndGetWithLimit(Lists.newArrayList(addAndGetRequest))
                .get(LimitKey.fromRequest(addAndGetRequest)))
        .isEqualTo(9);

    addAndGetRequest =
        addAndGetRequestBuilder
            .withEventTimestamp(Instant.parse("2018-11-30T18:36:30.00Z"))
            .build();
    Assert.assertEquals(addAndGetRequest.getPreviousBucketCounterPercentage(), 0.5, 0.00001);
    assertThat(
            storage
                .addAndGetWithLimit(Lists.newArrayList(addAndGetRequest))
                .get(LimitKey.fromRequest(addAndGetRequest)))
        .isEqualTo(7);

    addAndGetRequest =
        addAndGetRequestBuilder
            .withEventTimestamp(Instant.parse("2018-11-30T18:36:45.00Z"))
            .build();
    Assert.assertEquals(addAndGetRequest.getPreviousBucketCounterPercentage(), 0.25, 0.00001);
    assertThat(
            storage
                .addAndGetWithLimit(Lists.newArrayList(addAndGetRequest))
                .get(LimitKey.fromRequest(addAndGetRequest)))
        .isEqualTo(6);
  }
}
