package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import java.util.Objects;

/** Installs the deterministic V2 frame boundary after a successful WebSocket upgrade. */
public final class V2FramePipeline {
    private V2FramePipeline() {
    }

    public static void install(ChannelPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        pipeline.addLast("v2-frame-aggregator",
                new WebSocketFrameAggregator(V2EnvelopeDecoder.MAX_WIRE_BYTES));
        pipeline.addLast("v2-envelope-decoder", new V2EnvelopeDecoder());
        pipeline.addLast("v2-frame-error-normalizer", new V2FrameExceptionNormalizer());
        pipeline.addLast("v2-envelope-encoder", new V2EnvelopeEncoder());
        pipeline.addLast("v2-frame-close", new V2FrameCloseHandler());
    }
}
