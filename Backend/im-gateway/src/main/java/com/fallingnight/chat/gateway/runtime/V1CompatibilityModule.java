package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticationService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequestService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDirectoryService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchService;
import com.fallingnight.chat.gateway.compatibility.v1.V1AccountConnectionRegistry;
import com.fallingnight.chat.gateway.compatibility.v1.V1AuthenticationTimeoutHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1HeartbeatHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendDirectoryHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestAcceptanceEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestAcceptanceHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestCreationEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestCreationHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestRejectionEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRequestRejectionHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRemovalEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1FriendRemovalHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonFriendDirectoryCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonFriendRequestAcceptanceCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonFriendRequestCreationCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonFriendRequestRejectionCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonFriendRemovalCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonPendingFriendRequestCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1PendingFriendRequestEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1PendingFriendRequestHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonLifecycleCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonLoginCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonRoomDirectoryCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomDirectoryEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1RoomDirectoryHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonUserSearchCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1UserSearchEventSink;
import com.fallingnight.chat.gateway.compatibility.v1.V1UserSearchHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebLoginHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebSocketUpgradeHandler;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.identity.crypto.Argon2idCredentialHasher;
import com.fallingnight.chat.identity.crypto.CompatibleCredentialVerifier;
import com.fallingnight.chat.persistence.postgres.PostgresIdentityAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresConversationDirectoryAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1AccountProjection;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1ConversationProjection;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1FriendDirectoryAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1FriendRequestAcceptanceAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1FriendRequestCreationAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1FriendRequestDecisionAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1FriendRemovalAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1PendingFriendRequestAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1UserSearchAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Real V1 login composition kept detached from the product listener. */
public final class V1CompatibilityModule {
    private final LegacyV1LoginService login;
    private final LegacyV1RoomDirectoryService roomDirectory;
    private final LegacyV1FriendDirectoryService friendDirectory;
    private final LegacyV1PendingFriendRequestService pendingRequests;
    private final LegacyV1FriendRequestAcceptanceService friendRequestAcceptance;
    private final LegacyV1FriendRequestCreationService friendRequestCreation;
    private final LegacyV1FriendRequestRejectionService friendRequestRejection;
    private final LegacyV1FriendRemovalService friendRemoval;
    private final LegacyV1UserSearchService userSearch;
    private final Clock clock;
    private final V1AccountConnectionRegistry connections;

    private V1CompatibilityModule(
            LegacyV1LoginService login,
            LegacyV1RoomDirectoryService roomDirectory,
            LegacyV1FriendDirectoryService friendDirectory,
            LegacyV1PendingFriendRequestService pendingRequests,
            LegacyV1FriendRequestCreationService friendRequestCreation,
            LegacyV1FriendRequestAcceptanceService friendRequestAcceptance,
            LegacyV1FriendRequestRejectionService friendRequestRejection,
            LegacyV1FriendRemovalService friendRemoval,
            LegacyV1UserSearchService userSearch,
            Clock clock,
            V1AccountConnectionRegistry connections) {
        this.login = Objects.requireNonNull(login, "login");
        this.roomDirectory = Objects.requireNonNull(roomDirectory, "roomDirectory");
        this.friendDirectory = Objects.requireNonNull(friendDirectory, "friendDirectory");
        this.pendingRequests = Objects.requireNonNull(pendingRequests, "pendingRequests");
        this.friendRequestCreation = Objects.requireNonNull(
                friendRequestCreation, "friendRequestCreation");
        this.friendRequestAcceptance = Objects.requireNonNull(
                friendRequestAcceptance, "friendRequestAcceptance");
        this.friendRequestRejection = Objects.requireNonNull(
                friendRequestRejection, "friendRequestRejection");
        this.friendRemoval = Objects.requireNonNull(friendRemoval, "friendRemoval");
        this.userSearch = Objects.requireNonNull(userSearch, "userSearch");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public static V1CompatibilityModule create(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(clock, "clock");
        PostgresIdentityAdapter identity = new PostgresIdentityAdapter(dataSource);
        PostgresLegacyV1AccountProjection legacy =
                new PostgresLegacyV1AccountProjection(dataSource);
        LegacyV1AuthenticationService authentication =
                new LegacyV1AuthenticationService(
                        identity,
                        new CompatibleCredentialVerifier(),
                        identity,
                        new Argon2idCredentialHasher(),
                        identity,
                        legacy,
                        clock);
        PostgresLegacyV1ConversationProjection legacyConversations =
                new PostgresLegacyV1ConversationProjection(dataSource);
        V1AccountConnectionRegistry connections = new V1AccountConnectionRegistry();
        return new V1CompatibilityModule(
                new LegacyV1LoginService(authentication, legacy),
                new LegacyV1RoomDirectoryService(
                        new PostgresConversationDirectoryAdapter(dataSource), legacyConversations),
                new LegacyV1FriendDirectoryService(
                        new PostgresLegacyV1FriendDirectoryAdapter(dataSource),
                        legacyConversations,
                        legacy,
                        connections::onlineAccounts),
                new LegacyV1PendingFriendRequestService(
                        new PostgresLegacyV1PendingFriendRequestAdapter(dataSource)),
                new LegacyV1FriendRequestCreationService(
                        new PostgresLegacyV1FriendRequestCreationAdapter(dataSource)),
                new LegacyV1FriendRequestAcceptanceService(
                        new PostgresLegacyV1FriendRequestAcceptanceAdapter(dataSource)),
                new LegacyV1FriendRequestRejectionService(
                        new PostgresLegacyV1FriendRequestDecisionAdapter(dataSource)),
                new LegacyV1FriendRemovalService(
                        new PostgresLegacyV1FriendRemovalAdapter(dataSource)),
                new LegacyV1UserSearchService(
                        new PostgresLegacyV1UserSearchAdapter(dataSource),
                        connections::onlineAccounts),
                clock,
                connections);
    }

    public V1WebSocketUpgradeHandler newWebSocketUpgradeHandler(
            Executor authenticationExecutor,
            Executor directoryExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            V1RoomDirectoryEventSink directoryEvents,
            V1FriendDirectoryEventSink friendEvents,
            V1PendingFriendRequestEventSink pendingEvents,
            V1FriendRequestCreationEventSink creationEvents,
            V1FriendRequestAcceptanceEventSink acceptanceEvents,
            V1FriendRequestRejectionEventSink rejectionEvents,
            V1FriendRemovalEventSink removalEvents,
            V1UserSearchEventSink searchEvents,
            Duration upgradeTimeout,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout) {
        return new V1WebSocketUpgradeHandler(
                pipeline -> installWebApplicationPipeline(
                        pipeline,
                        authenticationExecutor,
                        directoryExecutor,
                        admission,
                        events,
                        directoryEvents,
                        friendEvents,
                        pendingEvents,
                        creationEvents,
                        acceptanceEvents,
                        rejectionEvents,
                        removalEvents,
                        searchEvents,
                        authenticationTimeout,
                        authenticatedIdleTimeout),
                upgradeTimeout);
    }

    private void installWebApplicationPipeline(
            ChannelPipeline pipeline,
            Executor authenticationExecutor,
            Executor directoryExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            V1RoomDirectoryEventSink directoryEvents,
            V1FriendDirectoryEventSink friendEvents,
            V1PendingFriendRequestEventSink pendingEvents,
            V1FriendRequestCreationEventSink creationEvents,
            V1FriendRequestAcceptanceEventSink acceptanceEvents,
            V1FriendRequestRejectionEventSink rejectionEvents,
            V1FriendRemovalEventSink removalEvents,
            V1UserSearchEventSink searchEvents,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(authenticatedIdleTimeout, "authenticatedIdleTimeout");
        if (authenticatedIdleTimeout.isZero() || authenticatedIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("authenticatedIdleTimeout must be positive");
        }
        V1JsonLifecycleCodec lifecycleCodec = new V1JsonLifecycleCodec(clock);
        pipeline.addLast("v1-authentication-timeout", new V1AuthenticationTimeoutHandler(
                authenticationTimeout));
        pipeline.addLast("v1-authenticated-idle-state", new IdleStateHandler(
                authenticatedIdleTimeout.toMillis(), 0, 0, TimeUnit.MILLISECONDS));
        pipeline.addLast("v1-login", new V1WebLoginHandler(
                login,
                new V1JsonLoginCodec(clock),
                lifecycleCodec,
                connections,
                authenticationExecutor,
                admission,
                events));
        pipeline.addLast("v1-heartbeat", new V1HeartbeatHandler(lifecycleCodec));
        pipeline.addLast("v1-room-directory", new V1RoomDirectoryHandler(
                roomDirectory,
                new V1JsonRoomDirectoryCodec(clock),
                directoryExecutor,
                directoryEvents));
        pipeline.addLast("v1-friend-directory", new V1FriendDirectoryHandler(
                friendDirectory,
                new V1JsonFriendDirectoryCodec(clock),
                directoryExecutor,
                friendEvents));
        pipeline.addLast("v1-pending-friend-requests", new V1PendingFriendRequestHandler(
                pendingRequests,
                new V1JsonPendingFriendRequestCodec(clock),
                directoryExecutor,
                pendingEvents));
        pipeline.addLast("v1-friend-request-creation", new V1FriendRequestCreationHandler(
                friendRequestCreation,
                new V1JsonFriendRequestCreationCodec(clock),
                connections,
                directoryExecutor,
                creationEvents));
        pipeline.addLast("v1-friend-request-acceptance", new V1FriendRequestAcceptanceHandler(
                friendRequestAcceptance,
                new V1JsonFriendRequestAcceptanceCodec(clock),
                connections,
                directoryExecutor,
                acceptanceEvents));
        pipeline.addLast("v1-friend-request-rejection", new V1FriendRequestRejectionHandler(
                friendRequestRejection,
                new V1JsonFriendRequestRejectionCodec(clock),
                directoryExecutor,
                rejectionEvents));
        pipeline.addLast("v1-friend-removal", new V1FriendRemovalHandler(
                friendRemoval,
                new V1JsonFriendRemovalCodec(clock),
                connections,
                directoryExecutor,
                removalEvents));
        pipeline.addLast("v1-user-search", new V1UserSearchHandler(
                userSearch,
                new V1JsonUserSearchCodec(clock),
                directoryExecutor,
                searchEvents));
    }
}
