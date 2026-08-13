package com.fallingnight.chat.routing.redis;

import com.fallingnight.chat.application.routing.*;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.ScriptOutputType;
import java.time.Instant;
import java.util.*;

/** Standalone Redis lease/routes and bounded payload-free target streams. */
public final class LettuceGatewayRoutingAdapter
        implements GatewayRouteLeasePort, GatewayLiveEventPublishPort,
        GatewayLiveEventConsumePort, AutoCloseable {
    private static final String PREFIX = "chat:v2:";
    private static final String PUBLISH_ROUTE = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
            return 1
            """;
    private static final String FIND_ROUTES = """
            local values = redis.call('ZRANGEBYSCORE', KEYS[1], '(' .. ARGV[1], '+inf',
                                      'LIMIT', 0, ARGV[4])
            local result = {}
            local complete = 1
            for _, gateway in ipairs(values) do
              if redis.call('GET', ARGV[2] .. gateway .. ':lease') == gateway then
                table.insert(result, gateway)
                if #result > tonumber(ARGV[3]) then complete = 0; break end
              else redis.call('ZREM', KEYS[1], gateway) end
            end
            if #values == tonumber(ARGV[4]) then complete = 0 end
            table.insert(result, 1, tostring(complete))
            return result
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public LettuceGatewayRoutingAdapter(RedisRoutingConfig config) {
        Objects.requireNonNull(config, "config");
        RedisURI redisUri = RedisURI.create(config.uri());
        redisUri.setTimeout(config.commandTimeout());
        client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder().autoReconnect(true)
                .pingBeforeActivateConnection(true)
                .requestQueueSize(config.requestQueueSize()).build());
        try {
            connection = client.connect();
            commands = connection.sync();
            commands.ping();
        } catch (RuntimeException exception) {
            client.shutdown();
            throw new RedisRoutingException("Redis routing connection failed", exception);
        }
    }

    @Override public boolean renewGateway(GatewayRouteLease lease) {
        Objects.requireNonNull(lease, "lease");
        long ttl = Math.max(1, lease.expiresAt().toEpochMilli() - lease.renewedAt().toEpochMilli());
        return execute(() -> "OK".equals(commands.set(gatewayLease(lease.gatewayId()),
                lease.gatewayId().toString(), SetArgs.Builder.px(ttl))));
    }

    @Override public boolean publishConversationRoute(ConversationGatewayRoute route) {
        Objects.requireNonNull(route, "route");
        Long result = execute(() -> commands.eval(PUBLISH_ROUTE, ScriptOutputType.INTEGER,
                new String[] {gatewayLease(route.gatewayId()), routes(route.conversationId())},
                route.gatewayId().toString(), Long.toString(route.expiresAt().toEpochMilli())));
        return result == 1;
    }

    @Override public ConversationGatewayRoutePage findConversationGateways(
            UUID conversationId, Instant observedAt, int limit) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(observedAt, "observedAt");
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be in 1..64");
        List<String> result = execute(() -> commands.eval(FIND_ROUTES,
                ScriptOutputType.MULTI, new String[] {routes(conversationId)},
                Long.toString(observedAt.toEpochMilli()), PREFIX + "gateway:",
                Integer.toString(limit), "1024"));
        if (result.isEmpty()) throw new RedisRoutingException("Redis route result invalid");
        boolean complete = "1".equals(result.getFirst());
        return new ConversationGatewayRoutePage(result.stream().skip(1).limit(limit)
                .map(UUID::fromString).toList(), complete);
    }

    @Override public boolean removeConversationRoute(UUID gatewayId, UUID conversationId) {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(conversationId, "conversationId");
        return execute(() -> commands.zrem(routes(conversationId), gatewayId.toString()) > 0);
    }

    @Override public boolean releaseGateway(UUID gatewayId) {
        Objects.requireNonNull(gatewayId, "gatewayId");
        return execute(() -> commands.del(gatewayLease(gatewayId)) > 0);
    }

    @Override public PublishResult publish(GatewayLiveEventHint hint, int maximumStreamLength) {
        Objects.requireNonNull(hint, "hint");
        if (maximumStreamLength < 100 || maximumStreamLength > 100_000)
            throw new IllegalArgumentException("maximumStreamLength outside reviewed range");
        try {
            String id = commands.xadd(stream(hint.targetGatewayId()),
                    XAddArgs.Builder.maxlen(maximumStreamLength),
                    Map.of("event", hint.eventId().toString(),
                            "conversation", hint.conversationId().toString(),
                            "sequence", Long.toString(hint.conversationSequence())));
            return id == null ? PublishResult.DEPENDENCY_REJECTED : PublishResult.PUBLISHED;
        } catch (RuntimeException exception) {
            return PublishResult.DEPENDENCY_UNAVAILABLE;
        }
    }

    @Override @SuppressWarnings("unchecked") public GatewayLiveEventBatch readAfter(
            UUID gatewayId, String afterStreamId, int limit) {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(afterStreamId, "afterStreamId");
        if (!afterStreamId.matches("[0-9]+-[0-9]+") || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("invalid gateway live event read");
        }
        var messages = execute(() -> commands.xread(XReadArgs.Builder.count(limit),
                XReadArgs.StreamOffset.from(stream(gatewayId), afterStreamId)));
        List<GatewayLiveEventStreamEntry> entries = messages.stream().map(message -> {
            Map<String, String> body = message.getBody();
            if (body.size() != 3 || !body.keySet().equals(
                    Set.of("event", "conversation", "sequence"))) {
                throw new RedisRoutingException("Redis event hint shape invalid");
            }
            try {
                return new GatewayLiveEventStreamEntry(message.getId(),
                        new GatewayLiveEventHint(gatewayId,
                                UUID.fromString(body.get("event")),
                                UUID.fromString(body.get("conversation")),
                                Long.parseLong(body.get("sequence"))));
            } catch (IllegalArgumentException exception) {
                throw new RedisRoutingException("Redis event hint value invalid", exception);
            }
        }).toList();
        return new GatewayLiveEventBatch(afterStreamId, entries);
    }

    @Override public void close() {
        connection.close();
        client.shutdown();
    }

    private <T> T execute(java.util.concurrent.Callable<T> operation) {
        try { return operation.call(); }
        catch (Exception exception) { throw new RedisRoutingException("Redis routing operation failed", exception); }
    }
    private static String gatewayLease(UUID gateway) { return PREFIX + "gateway:" + gateway + ":lease"; }
    private static String routes(UUID conversation) { return PREFIX + "conversation:" + conversation + ":gateways"; }
    private static String stream(UUID gateway) { return PREFIX + "gateway:" + gateway + ":events"; }
}
