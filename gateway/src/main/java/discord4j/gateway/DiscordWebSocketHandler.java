/*
 * This file is part of Discord4J.
 *
 * Discord4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Discord4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Discord4J. If not, see <http://www.gnu.org/licenses/>.
 */
package discord4j.gateway;

import discord4j.common.close.CloseStatus;
import discord4j.gateway.retry.DisconnectBehavior;
import discord4j.gateway.retry.PartialDisconnectException;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;
import reactor.util.function.Tuple2;

import static discord4j.common.LogUtil.format;

/**
 * Represents a WebSocket handler specialized for Discord gateway operations.
 * <p>
 * Includes a zlib-based decompressor and dedicated handling of closing events that normally occur during Discord
 * gateway lifecycle.
 * <p>
 * This handler uses a {@link FluxSink} of {@link ByteBuf} to push inbound payloads and a {@link Flux} of
 * {@link ByteBuf} to pull outbound payloads.
 * <p>
 * The handler also provides methods to control the lifecycle, which perform operations on the current session. It is
 * required to use them to properly release important resources and complete the session.
 */
public class DiscordWebSocketHandler {

    private static final Logger log = Loggers.getLogger(DiscordWebSocketHandler.class);

    private final FluxSink<ByteBuf> inbound;
    private final Flux<ByteBuf> outbound;
    private final MonoProcessor<DisconnectBehavior> sessionClose;
    private final ZlibDecompressor decompressor = new ZlibDecompressor();
    private final Context context;

    /**
     * Create a new handler with the given data pipelines.
     *
     * @param inbound the {@link FluxSink} of {@link ByteBuf} to process inbound payloads
     * @param outbound the {@link Flux} of {@link ByteBuf} to process outbound payloads
     * @param context the Reactor {@link Context} that owns this handler, to enrich logging
     */
    public DiscordWebSocketHandler(FluxSink<ByteBuf> inbound, Flux<ByteBuf> outbound, Context context) {
        this.inbound = inbound;
        this.outbound = outbound;
        this.sessionClose = MonoProcessor.create();
        this.context = context;
    }

    /**
     * Handle an upgraded websocket connection, given by both {@link WebsocketInbound} and {@link WebsocketOutbound} to
     * manage a session until the remote closes or one of the local methods {@link #close()} or
     * {@link #error(Throwable)} methods are called. When that happens, a close procedure will take place and ultimately
     * emit a pair of {@link DisconnectBehavior} and remote {@link CloseStatus}, if present or "-1" if none is present.
     *
     * @param in the websocket inbound
     * @param out the websocket outbound
     * @return a {@link Mono} that upon subscription, manages a websocket session until it closes where a {@link Tuple2}
     * is emitted representing both the {@link DisconnectBehavior} that initiated the close procedure, and the inbound
     * {@link CloseStatus}.
     */
    public Mono<Tuple2<DisconnectBehavior, CloseStatus>> handle(WebsocketInbound in, WebsocketOutbound out) {
        Mono<CloseWebSocketFrame> outboundClose = sessionClose
                .doOnNext(behavior -> log.debug(format(context, "Closing session with behavior: {}"), behavior))
                .flatMap(behavior -> {
                    switch (behavior.getAction()) {
                        case RETRY_ABRUPTLY:
                        case STOP_ABRUPTLY:
                            return Mono.error(behavior.getCause() != null ? behavior.getCause() :
                                    new PartialDisconnectException(context));
                        case RETRY:
                        case STOP:
                        default:
                            return Mono.just(CloseStatus.NORMAL_CLOSE);
                    }
                })
                .map(status -> new CloseWebSocketFrame(status.getCode(), status.getReason().orElse(null)));

        Mono<CloseStatus> inboundClose = in.receiveCloseStatus()
                .map(status -> new CloseStatus(status.code(), status.reasonText()));

        Mono<Void> outboundEvents = out.sendObject(Flux.merge(outboundClose, outbound.map(TextWebSocketFrame::new)))
                .then();

        Mono<Void> inboundEvents = in.aggregateFrames()
                .receiveFrames()
                .map(WebSocketFrame::content)
                .transformDeferred(decompressor::completeMessages)
                .doOnNext(inbound::next)
                .then();

        return Mono.zip(outboundEvents, inboundEvents)
                .doOnError(this::error)
                .then(Mono.zip(sessionClose, inboundClose));
    }

    /**
     * Initiates a close sequence that will terminate this session and instruct consumers downstream that a reconnect
     * should take place afterwards.
     */
    public void close() {
        close(DisconnectBehavior.retry(null));
    }

    /**
     * Initiates a close sequence that will terminate this session and then execute a given {@link DisconnectBehavior}.
     *
     * @param behavior the {@link DisconnectBehavior} to follow after the close sequence starts
     */
    public void close(DisconnectBehavior behavior) {
        sessionClose.onNext(behavior);
    }

    /**
     * Initiates a close sequence with the given error. The session will be terminated abruptly and then instruct
     * consumers downstream that a reconnect should take place afterwards.
     *
     * @param error the cause for this session termination
     */
    public void error(Throwable error) {
        close(DisconnectBehavior.retryAbruptly(error));
    }
}
