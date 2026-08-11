package com.fallingnight.chat.gateway;

import com.fallingnight.chat.application.ApplicationModule;
import com.fallingnight.chat.gateway.runtime.GatewayRuntime;
import com.fallingnight.chat.gateway.runtime.GatewayRuntimeConfig;
import com.fallingnight.chat.protocol.V2Protocol;

/** Environment-only composition root for the independently runnable V2 gateway. */
public final class GatewayMain {
    private static final System.Logger LOGGER = System.getLogger(GatewayMain.class.getName());

    private GatewayMain() {
    }

    public static String identity() {
        return "im-gateway->" + ApplicationModule.NAME + ":v" + V2Protocol.VERSION;
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("gateway accepts no command-line arguments");
        }
        GatewayRuntime runtime = null;
        Thread shutdownHook = null;
        try {
            GatewayRuntimeConfig config = GatewayRuntimeConfig.fromEnvironment(System.getenv());
            runtime = GatewayRuntime.create(config);
            GatewayRuntime owned = runtime;
            shutdownHook = new Thread(owned::close, "chat-gateway-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            runtime.start();
            LOGGER.log(System.Logger.Level.INFO, "event=gateway_ready");
            runtime.awaitTermination();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "event=gateway_start_failed type=" + exception.getClass().getSimpleName());
            throw new IllegalStateException("gateway startup failed");
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            removeShutdownHook(shutdownHook);
        }
    }

    private static void removeShutdownHook(Thread hook) {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException exception) {
            // JVM shutdown is already running the hook.
        }
    }
}
