/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.client.messaging.internal;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.ditto.client.configuration.AuthenticationConfiguration;
import org.eclipse.ditto.client.configuration.MessagingConfiguration;
import org.eclipse.ditto.client.internal.DefaultThreadFactory;
import org.eclipse.ditto.client.internal.VersionReader;
import org.eclipse.ditto.client.internal.bus.AdaptableBus;
import org.eclipse.ditto.client.internal.bus.BusFactory;
import org.eclipse.ditto.client.messaging.AuthenticationException;
import org.eclipse.ditto.client.messaging.AuthenticationProvider;
import org.eclipse.ditto.client.messaging.MessagingException;
import org.eclipse.ditto.client.messaging.MessagingProvider;
import org.eclipse.ditto.json.JsonCollectors;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.common.DittoConstants;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.StatusLine;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketError;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

/**
 * Messaging Provider providing messaging access to Ditto WebSocket which is directly provided by Eclipse Ditto
 * Gateway.
 *
 * @since 1.0.0
 */
public final class WebSocketMessagingProvider extends WebSocketAdapter implements MessagingProvider {

    private static final String DITTO_CLIENT_USER_AGENT = "DittoClient/" + VersionReader.determineClientVersion();
    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketMessagingProvider.class);
    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int RECONNECTION_TIMEOUT_SECONDS = 5;

    private final AdaptableBus adaptableBus;
    private final MessagingConfiguration messagingConfiguration;
    private final AuthenticationProvider<WebSocket> authenticationProvider;
    private final ExecutorService callbackExecutor;
    private final String sessionId;
    private final ScheduledExecutorService reconnectExecutor;
    private final Map<Object, String> subscriptionMessages;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicBoolean initiallyConnected = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private final AtomicReference<WebSocket> webSocket;

    /**
     * Constructs a new {@code WsMessagingProvider}.
     *
     * @param adaptableBus the bus to publish all messages to.
     * @param messagingConfiguration the specific configuration to apply.
     * @param authenticationProvider provider for the authentication method with which to open the websocket.
     * @param callbackExecutor the executor service to run callbacks with.
     */
    private WebSocketMessagingProvider(final AdaptableBus adaptableBus,
            final MessagingConfiguration messagingConfiguration,
            final AuthenticationProvider<WebSocket> authenticationProvider,
            final ExecutorService callbackExecutor) {
        this.adaptableBus = adaptableBus;
        this.messagingConfiguration = messagingConfiguration;
        this.authenticationProvider = authenticationProvider;
        this.callbackExecutor = callbackExecutor;

        sessionId = authenticationProvider.getConfiguration().getSessionId();
        reconnectExecutor = createReconnectExecutor();
        subscriptionMessages = new ConcurrentHashMap<>();
        webSocket = new AtomicReference<>();
    }

    private static ScheduledExecutorService createReconnectExecutor() {
        return Executors.newScheduledThreadPool(1, new DefaultThreadFactory("ditto-client-reconnect"));
    }

    /**
     * Returns a new {@code WebSocketMessagingProvider}.
     *
     * @param messagingConfiguration configuration of messaging.
     * @param authenticationProvider provides authentication.
     * @param callbackExecutor the executor for messages.
     * @return the provider.
     */
    public static WebSocketMessagingProvider newInstance(final MessagingConfiguration messagingConfiguration,
            final AuthenticationProvider<WebSocket> authenticationProvider,
            final ExecutorService callbackExecutor) {
        checkNotNull(messagingConfiguration, "messagingConfiguration");
        checkNotNull(authenticationProvider, "authenticationProvider");
        checkNotNull(callbackExecutor, "callbackExecutor");

        final AdaptableBus adaptableBus = BusFactory.createAdaptableBus();
        return new WebSocketMessagingProvider(adaptableBus, messagingConfiguration, authenticationProvider,
                callbackExecutor);
    }

    @Override
    public AuthenticationConfiguration getAuthenticationConfiguration() {
        return authenticationProvider.getConfiguration();
    }

    @Override
    public MessagingConfiguration getMessagingConfiguration() {
        return messagingConfiguration;
    }

    @Override
    public ExecutorService getExecutorService() {
        return callbackExecutor;
    }

    @Override
    public AdaptableBus getAdaptableBus() {
        return adaptableBus;
    }

    @Override
    public MessagingProvider registerSubscriptionMessage(final Object key, final String message) {
        subscriptionMessages.put(key, message);
        return this;
    }

    @Override
    public MessagingProvider unregisterSubscriptionMessage(final Object key) {
        subscriptionMessages.remove(key);
        return this;
    }

    @Override
    public synchronized void initialize() {
        // this method may be called multiple times.
        synchronized (initiallyConnected) {
            if (webSocket.get() == null) {
                final ScheduledExecutorService executor = createConnectionExecutor();
                try {
                    setWebSocket(connectWithRetries(this::createWebsocket, executor).toCompletableFuture().join());
                    initiallyConnected.set(true);
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    private WebSocket createWebsocket() {
        final WebSocketFactory webSocketFactory = WebSocketFactoryFactory.newWebSocketFactory(messagingConfiguration);
        final WebSocket ws;
        try {
            final String declaredAcksJsonArrayString = messagingConfiguration.getDeclaredAcknowledgements()
                    .stream()
                    .map(AcknowledgementLabel::toString)
                    .map(JsonValue::of)
                    .collect(JsonCollectors.valuesToArray())
                    .toString();
            ws = webSocketFactory.createSocket(messagingConfiguration.getEndpointUri())
                    .addHeader(DittoConstants.WEBSOCKET_SESSION_HEADER_DECLARED_ACKS, declaredAcksJsonArrayString);
        } catch (final IOException e) {
            throw MessagingException.connectFailed(sessionId, e);
        }
        return ws;
    }

    /**
     * Initiates the connection to the web socket by using the provided {@code ws} and applying the passed {@code
     * webSocketListener} for web socket handling and incoming messages.
     *
     * @param ws the WebSocket instance to use for connecting.
     * @return The connected websocket.
     * be empty.
     * @throws NullPointerException if any argument is {@code null}.
     */
    private CompletionStage<WebSocket> initiateConnection(final WebSocket ws, final ExecutorService executor) {
        checkNotNull(ws, "ws");
        checkNotNull(executor, "executor");

        // TODO: make these configurable?
        ws.addHeader("User-Agent", DITTO_CLIENT_USER_AGENT);
        ws.setMaxPayloadSize(256 * 1024); // 256 KiB
        ws.setMissingCloseFrameAllowed(true);
        ws.setFrameQueueSize(0);
        ws.setPingInterval(CONNECTION_TIMEOUT_MS);
        authenticationProvider.prepareAuthentication(ws);
        ws.addListener(this);

        LOGGER.info("Connecting WebSocket on endpoint <{}>.", ws.getURI());
        final CompletableFuture<WebSocket> webSocketFuture = new CompletableFuture<>();
        final Callable<WebSocket> connectCallable = ws.connectable();
        executor.submit(() -> {
            try {
                webSocketFuture.complete(connectCallable.call());
            } catch (final Throwable e) {
                webSocketFuture.completeExceptionally(mapConnectError(e));
            }
        });
        return webSocketFuture;
    }

    private static ScheduledExecutorService createConnectionExecutor() {
        return Executors.newScheduledThreadPool(1, new DefaultThreadFactory("ditto-client-connect"));
    }

    @Override
    public void emit(final String message) {
        sendToWebsocket(message);
    }

    private void sendToWebsocket(final String stringMessage) {
        final WebSocket ws = webSocket.get();
        if (ws != null && ws.isOpen()) {
            LOGGER.debug("Client <{}>: Sending: {}", sessionId, stringMessage);
            ws.sendText(stringMessage);
        } else {
            LOGGER.error("Client <{}>: WebSocket is not connected - going to discard message '{}'",
                    sessionId, stringMessage);
        }
    }

    @Override
    public void close() {
        synchronized (isCancelled) {
            if (!isCancelled.get()) {
                try {
                    LOGGER.debug("Client <{}>: Closing WebSocket client of endpoint <{}>.", sessionId,
                            messagingConfiguration.getEndpointUri());

                    cancelScheduledReconnect();
                    authenticationProvider.destroy();
                    final WebSocket ws = webSocket.get();
                    if (ws != null) {
                        ws.disconnect();
                    }

                    LOGGER.info("Client <{}>: WebSocket destroyed.", sessionId);
                } catch (final Exception e) {
                    LOGGER.info("Client <{}>: Exception occurred while trying to shutdown http client.", sessionId, e);
                }
            }
        }
    }

    @Override
    public void onConnected(final WebSocket websocket, final Map<String, List<String>> headers) {
        callbackExecutor.execute(() -> {
            LOGGER.info("Client <{}>: WebSocket connection is established", sessionId);

            if (initiallyConnected.get()) {
                // we were already connected - so this is a reconnect
                LOGGER.info("Client <{}>: Subscribing again for messages from backend after reconnection",
                        sessionId);
                subscriptionMessages.values().forEach(this::emit);
            }
        });
    }


    @Override
    public void onDisconnected(final WebSocket websocket, final WebSocketFrame serverCloseFrame,
            final WebSocketFrame clientCloseFrame,
            final boolean closedByServer) {

        callbackExecutor.execute(() -> {
            if (closedByServer) {
                LOGGER.info(
                        "Client <{}>: WebSocket connection to endpoint <{}> was closed by Server with code <{}> and " +
                                "reason <{}>.", sessionId, messagingConfiguration.getEndpointUri(),
                        serverCloseFrame.getCloseCode(),
                        serverCloseFrame.getCloseReason());
                handleReconnectionIfEnabled();
            } else {
                LOGGER.info("Client <{}>: WebSocket connection to endpoint <{}> was closed by client",
                        sessionId, messagingConfiguration.getEndpointUri());
            }
        });
    }

    @Override
    public void onError(final WebSocket websocket, final WebSocketException cause) {
        callbackExecutor.execute(() -> {
            final String msgPattern = "Client <{}>: Error in WebSocket: {}";
            final String errorMsg; // avoids cluttering the log
            if (null != cause) {
                errorMsg = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } else {
                errorMsg = "-";
            }
            LOGGER.error(msgPattern, sessionId, errorMsg);
            handleReconnectionIfEnabled();
        });
    }

    private CompletionStage<WebSocket> connectWithRetries(final Supplier<WebSocket> webSocket,
            final ScheduledExecutorService executorService) {

        return Retry.retryTo("initialize WebSocket connection",
                () -> initiateConnection(webSocket.get(), reconnectExecutor), isCancelled::get)
                .inClientSession(sessionId)
                .withExecutor(executorService)
                .notifyOnError(messagingConfiguration.getConnectionErrorHandler().orElse(null))
                .isRecoverable(WebSocketMessagingProvider::isRecoverable)
                .get();
    }

    private void handleReconnectionIfEnabled() {

        if (messagingConfiguration.isReconnectEnabled()) {
            // reconnect in a while if client was initially connected and we are not reconnecting already
            if (initiallyConnected.get() && reconnecting.compareAndSet(false, true) && null != reconnectExecutor) {
                LOGGER.info("Client <{}>: Reconnection is enabled. Reconnecting in <{}> seconds ...",
                        sessionId, RECONNECTION_TIMEOUT_SECONDS);
                reconnectExecutor.schedule(this::reconnectWithRetries, RECONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } else {
            LOGGER.info("Client <{}>: Reconnection is NOT enabled. Closing client ...", sessionId);
            close();
        }
    }

    private void reconnectWithRetries() {
        this.connectWithRetries(this::recreateWebSocket, reconnectExecutor)
                .thenAccept(reconnectedWebSocket -> {
                    setWebSocket(reconnectedWebSocket);
                    reconnecting.set(false);
                });
    }

    private void setWebSocket(final WebSocket webSocket) {
        synchronized (this.webSocket) {
            final WebSocket oldWebSocket = this.webSocket.get();
            this.webSocket.set(webSocket);
            try {
                if (oldWebSocket != null && oldWebSocket != webSocket) {
                    oldWebSocket.disconnect();
                }
            } catch (final Exception exception) {
                LOGGER.error("Client <{}>: Error disconnecting a previous websocket", sessionId, exception);
            }
        }
    }

    private WebSocket recreateWebSocket() {
        LOGGER.info("Recreating Websocket..");
        final WebSocket ws = webSocket.get();
        if (ws == null) {
            LOGGER.error("Client <{}>: attempt to recreate a null websocket", sessionId);
            throw new IllegalStateException("Cannot recreate a null websocket. This method should not have been " +
                    "called without having created a WebSocket before.");
        }
        ws.clearHeaders();
        ws.clearListeners();

        try {
            return ws.recreate(CONNECTION_TIMEOUT_MS);
        } catch (IOException e) {
            throw MessagingException.recreateFailed(sessionId, e);
        }
    }

    @Override
    public void onBinaryMessage(final WebSocket websocket, final byte[] binary) {
        callbackExecutor.execute(() -> {
            final String stringMessage = new String(binary, StandardCharsets.UTF_8);
            LOGGER.debug(
                    "Client <{}>: Received WebSocket byte array message <{}>, as string <{}> - don't know what to do" +
                            " with it!.", sessionId, binary, stringMessage);
        });
    }

    @Override
    public void onTextMessage(final WebSocket websocket, final String text) {
        callbackExecutor.execute(() -> {
            LOGGER.debug("Client <{}>: Received WebSocket string message <{}>", sessionId, text);
            handleIncomingMessage(text);
        });
    }

    private void handleIncomingMessage(final String message) {
        adaptableBus.publish(message);
    }

    private void cancelScheduledReconnect() {
        isCancelled.set(true);
        if (null != reconnectExecutor) {
            // Run the Retry tasks on the reconnect executor here
            // so that they release any threads waiting on them, because isCancelled(true)
            reconnectExecutor.shutdownNow().forEach(Runnable::run);
        }
    }

    private Throwable mapConnectError(final Throwable e) {
        final Throwable cause = (e instanceof CompletionException || e instanceof ExecutionException)
                ? e.getCause()
                : e;
        if (cause instanceof WebSocketException) {
            LOGGER.error("Got exception: {}", cause.getMessage());
            if (cause instanceof OpeningHandshakeException) {
                final StatusLine statusLine = ((OpeningHandshakeException) cause).getStatusLine();
                final HttpStatusCode httpStatusCode = HttpStatusCode.forInt(statusLine.getStatusCode())
                        .orElse(HttpStatusCode.INTERNAL_SERVER_ERROR);
                if (httpStatusCode.isClientError()) {
                    switch (httpStatusCode) {
                        case UNAUTHORIZED:
                            return AuthenticationException.unauthorized(sessionId, cause);
                        case FORBIDDEN:
                            return AuthenticationException.forbidden(sessionId, cause);
                        default:
                            return AuthenticationException.withStatus(sessionId, cause, statusLine.getStatusCode(),
                                    statusLine.getReasonPhrase() + ": " +
                                            new String(((OpeningHandshakeException) cause).getBody()));
                    }
                }
            } else if (((WebSocketException) cause).getError() == WebSocketError.SOCKET_CONNECT_ERROR &&
                    cause.getCause() instanceof UnknownHostException) {
                return MessagingException.connectFailed(sessionId, cause.getCause());
            }
        }

        return MessagingException.connectFailed(sessionId, cause);
    }

    /**
     * Test whether an exception to be delivered to error consumer can be recovered from.
     * Reconnection will be attempted only if it is.
     *
     * @param error the error to deliver to any configured error consumer.
     * @return whether the error can be recovered from.
     * @see this#mapConnectError(Throwable)
     */
    private static boolean isRecoverable(final Throwable error) {
        // every exception should be recoverable
        return true;
    }

}
