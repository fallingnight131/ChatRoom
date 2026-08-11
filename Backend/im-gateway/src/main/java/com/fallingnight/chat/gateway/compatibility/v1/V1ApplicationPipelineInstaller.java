package com.fallingnight.chat.gateway.compatibility.v1;

import io.netty.channel.ChannelPipeline;

/** Installs the detached V1 application handlers after a verified upgrade. */
@FunctionalInterface
public interface V1ApplicationPipelineInstaller {
    void install(ChannelPipeline pipeline);
}
