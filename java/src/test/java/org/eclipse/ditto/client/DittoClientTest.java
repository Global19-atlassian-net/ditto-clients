/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.eclipse.ditto.client.internal.AbstractDittoClientTest;
import org.eclipse.ditto.model.base.common.HttpStatus;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.things.ThingErrorResponse;
import org.junit.Test;

public final class DittoClientTest extends AbstractDittoClientTest {

    @Test
    public void concurrentTwinAndLiveSubscriptionWorks() {
        final CompletableFuture<Void> twinConsumption = client.twin().startConsumption();
        final CompletableFuture<Void> liveConsumption = client.live().startConsumption();

        expectProtocolMsgWithCorrelationId("START-SEND-EVENTS");
        expectProtocolMsgWithCorrelationId("START-SEND-LIVE-EVENTS");
        expectProtocolMsgWithCorrelationId("START-SEND-MESSAGES");
        expectProtocolMsgWithCorrelationId("START-SEND-LIVE-COMMANDS");

        reply("START-SEND-LIVE-COMMANDS:ACK");
        reply("START-SEND-MESSAGES:ACK");
        reply("START-SEND-LIVE-EVENTS:ACK");
        reply("START-SEND-EVENTS:ACK");

        assertCompletion(twinConsumption);
        assertCompletion(liveConsumption);
    }

    @Test
    public void concurrentTwinAndLiveSubscriptionWorksForIfTwinAndLiveFail() {
        final CompletableFuture<Void> twinConsumption = client.twin().startConsumption();
        final CompletableFuture<Void> liveConsumption = client.live().startConsumption();

        final Map<String, String> map = new HashMap<>();
        map.put("START-SEND-EVENTS", expectProtocolMsgWithCorrelationId("START-SEND-EVENTS"));
        map.put("START-SEND-LIVE-EVENTS", expectProtocolMsgWithCorrelationId("START-SEND-LIVE-EVENTS"));
        map.put("START-SEND-MESSAGES", expectProtocolMsgWithCorrelationId("START-SEND-MESSAGES"));
        map.put("START-SEND-LIVE-COMMANDS", expectProtocolMsgWithCorrelationId("START-SEND-LIVE-COMMANDS"));
        map.forEach((key, value) -> reply(getErrorResponse(value, key)));

        assertFailedCompletion(twinConsumption, "START-SEND-EVENTS");
        assertFailedCompletion(liveConsumption, "START-SEND-LIVE-EVENTS");
    }

    @Test
    public void concurrentTwinAndLiveSubscriptionWorksIfTwinFailsAndLiveSucceeds() {
        final CompletableFuture<Void> twinConsumption = client.twin().startConsumption();
        final CompletableFuture<Void> liveConsumption = client.live().startConsumption();

        final String correlationId = expectProtocolMsgWithCorrelationId("START-SEND-EVENTS");
        expectProtocolMsgWithCorrelationId("START-SEND-LIVE-EVENTS");
        expectProtocolMsgWithCorrelationId("START-SEND-MESSAGES");
        expectProtocolMsgWithCorrelationId("START-SEND-LIVE-COMMANDS");

        reply("START-SEND-MESSAGES:ACK");
        reply("START-SEND-LIVE-COMMANDS:ACK");
        reply(getErrorResponse(correlationId, "START-SEND-EVENTS"));
        reply("START-SEND-LIVE-EVENTS:ACK");


        assertFailedCompletion(twinConsumption, "START-SEND-EVENTS");
        assertCompletion(liveConsumption);
    }

    private static Signal<?> getErrorResponse(final CharSequence correlationId, final String s) {
        final DittoHeaders dittoHeaders = DittoHeaders.newBuilder().correlationId(correlationId).build();
        return ThingErrorResponse.of(DittoRuntimeException.newBuilder("unexpected", HttpStatus.BAD_REQUEST)
                .message(s)
                .dittoHeaders(dittoHeaders)
                .build());
    }

    private static void assertFailedCompletion(final CompletableFuture<Void> twinConsumption, final String s) {
        try {
            twinConsumption.get(1, TimeUnit.SECONDS);
        } catch (final Exception e) {
            Assertions.assertThat(e)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DittoRuntimeException.class)
                    .matches(t -> s.equals(t.getCause().getMessage()));
        }
    }

}
