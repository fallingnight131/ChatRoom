package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.application.messaging.*;
import com.fallingnight.chat.application.routing.*;
import com.fallingnight.chat.persistence.postgres.*;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.routing.redis.LettuceGatewayRoutingAdapter;
import com.fallingnight.chat.routing.redis.RedisRoutingConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Default-off two-node routing proof over real PostgreSQL and Redis. */
final class TwoGatewayRedisPostgresIntegrationTest {
    private static final String POSTGRES_URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String POSTGRES_USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String POSTGRES_PASSWORD =
            System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");
    private static final String REDIS_URI = System.getenv("CHATROOM_TEST_REDIS_URI");

    @Test
    void commitsRelaysAndRepairsOneMessageAcrossTwoGatewayBootStreams() throws Exception {
        assumeTrue(POSTGRES_URL != null && !POSTGRES_URL.isBlank()
                && REDIS_URI != null && !REDIS_URI.isBlank(),
                "set disposable PostgreSQL and Redis endpoints");
        new PostgresMigrator(POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD).migrate();
        PGSimpleDataSource dataSource = dataSource();
        truncate(dataSource);
        UUID account = UUID.randomUUID(), device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seed(dataSource, account, device, conversation);

        RedisRoutingConfig redisConfig = new RedisRoutingConfig(
                REDIS_URI, Duration.ofSeconds(1), 64, true);
        UUID firstGateway = UUID.randomUUID(), secondGateway = UUID.randomUUID();
        Clock clock = Clock.systemUTC();
        SingleGatewayConversationLiveRouter firstRouter =
                new SingleGatewayConversationLiveRouter(clock);
        SingleGatewayConversationLiveRouter secondRouter =
                new SingleGatewayConversationLiveRouter(clock);
        EmbeddedChannel firstChannel = authenticated(account, device);
        EmbeddedChannel secondChannel = authenticated(account, device);

        try (var relayRedis = new LettuceGatewayRoutingAdapter(redisConfig);
                var firstRedis = new LettuceGatewayRoutingAdapter(redisConfig);
                var secondRedis = new LettuceGatewayRoutingAdapter(redisConfig)) {
            Duration lease = Duration.ofSeconds(30);
            var firstRegistration = new GatewayRouteRegistrationService(
                    firstRedis, firstGateway, lease, clock);
            var secondRegistration = new GatewayRouteRegistrationService(
                    secondRedis, secondGateway, lease, clock);
            assertTrue(firstRegistration.renewGateway());
            assertTrue(secondRegistration.renewGateway());

            PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource);
            MessageHistoryQuery initial = new MessageHistoryQuery(
                    conversation, account, 0, 100);
            assertFinalEmpty(firstRouter.readAndSubscribe(firstChannel, initial, messages));
            assertFinalEmpty(secondRouter.readAndSubscribe(secondChannel, initial, messages));
            assertTrue(firstRegistration.registerAfterCatchUp(
                    conversation, 0, (id, after) -> after).isPresent());
            assertTrue(secondRegistration.registerAfterCatchUp(
                    conversation, 0, (id, after) -> after).isPresent());

            MessageSubmissionResult.Accepted accepted =
                    (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                            conversation, account, device, "two-gateway-message", 1,
                            "cross-node".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var relay = new ConversationEventRelayService(
                    new PostgresConversationEventOutboxAdapter(dataSource),
                    new RoutedConversationEventPublisher(relayRedis, relayRedis, 8, 1_000, clock),
                    UUID.randomUUID(), Duration.ofSeconds(5), 10,
                    Duration.ofMillis(100), Duration.ofSeconds(5), clock);
            assertEquals(new ConversationEventRelayReport(1, 1, 0, 0, 0), relay.runOnce());

            GatewayLiveEventConsumerReport first = consume(firstRedis, firstGateway,
                    firstRouter, messages, "0-0");
            GatewayLiveEventConsumerReport second = consume(secondRedis, secondGateway,
                    secondRouter, messages, "0-0");
            assertEquals(1, first.applied()); assertEquals(1, second.applied());
            assertMessage(firstChannel, accepted.messageId(), 1, "cross-node");
            assertMessage(secondChannel, accepted.messageId(), 1, "cross-node");

            GatewayLiveEventHint duplicateFirst = new GatewayLiveEventHint(firstGateway,
                    accepted.messageId(), conversation, 1);
            GatewayLiveEventHint duplicateSecond = new GatewayLiveEventHint(secondGateway,
                    accepted.messageId(), conversation, 1);
            assertEquals(GatewayLiveEventPublishPort.PublishResult.PUBLISHED,
                    relayRedis.publish(duplicateFirst, 1_000));
            assertEquals(GatewayLiveEventPublishPort.PublishResult.PUBLISHED,
                    relayRedis.publish(duplicateSecond, 1_000));
            assertEquals(1, consume(firstRedis, firstGateway, firstRouter, messages,
                    first.nextStreamId()).duplicates());
            assertEquals(1, consume(secondRedis, secondGateway, secondRouter, messages,
                    second.nextStreamId()).duplicates());
            assertNull(firstChannel.readOutbound()); assertNull(secondChannel.readOutbound());
            assertEquals(1, count(dataSource,
                    "SELECT count(*) FROM chat.message WHERE conversation_id='"
                            + conversation + "'"));
            assertEquals(1, count(dataSource,
                    "SELECT count(*) FROM chat.conversation_event_outbox "
                            + "WHERE published_at IS NOT NULL"));
        } finally {
            firstChannel.finishAndReleaseAll(); secondChannel.finishAndReleaseAll();
        }
    }

    private static GatewayLiveEventConsumerReport consume(LettuceGatewayRoutingAdapter redis,
            UUID gateway, SingleGatewayConversationLiveRouter router,
            PostgresMessageAdapter messages, String cursor) {
        return new GatewayLiveEventConsumerService(redis,
                new LocalConversationMessageHintRepairAdapter(router, messages), gateway, 10)
                .runOnce(cursor);
    }

    private static void assertFinalEmpty(MessageHistoryResult result) {
        MessageHistoryResult.Page page = (MessageHistoryResult.Page) result;
        assertTrue(page.messages().isEmpty()); assertFalse(page.hasMore());
    }

    private static void assertMessage(EmbeddedChannel channel, UUID messageId,
            long sequence, String content) throws Exception {
        Envelope envelope = channel.readOutbound();
        MessageRecord record = MessageRecord.parseFrom(envelope.getPayload());
        assertEquals(messageId.toString(), record.getMessageId());
        assertEquals(sequence, record.getConversationSequence());
        assertEquals(content, record.getContent().toStringUtf8());
    }

    private static EmbeddedChannel authenticated(UUID account, UUID device) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(account, device, UUID.randomUUID()));
        return channel;
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource value = new PGSimpleDataSource(); value.setUrl(POSTGRES_URL);
        value.setUser(POSTGRES_USER); value.setPassword(POSTGRES_PASSWORD); return value;
    }
    private static void truncate(PGSimpleDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE chat.account, chat.conversation CASCADE")) {
            statement.execute();
        }
    }
    private static void seed(PGSimpleDataSource dataSource, UUID account,
            UUID device, UUID conversation) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "INSERT INTO chat.account(id,username_key,display_name,password_hash) "
                    + "VALUES (?, 'two-gateway', 'Two Gateway', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", account);
            execute(connection, "INSERT INTO chat.device(id,account_id,client_device_id,platform) "
                    + "VALUES (?,?,'two-gateway-device','WEB')", device, account);
            execute(connection, "INSERT INTO chat.conversation(id,kind) VALUES (?,'DIRECT')",
                    conversation);
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id,account_id) "
                    + "VALUES (?,?)", conversation, account);
        }
    }
    private static void execute(Connection connection, String sql, Object... values)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++)
                statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
    private static int count(PGSimpleDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) { result.next(); return result.getInt(1); }
    }
}
