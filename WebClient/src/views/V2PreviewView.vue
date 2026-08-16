<template>
  <main class="v2-preview">
    <header class="preview-header">
      <div>
        <span class="preview-badge">{{ shellMessages.engineeringPreview }}</span>
        <h1>ChatRoom V2</h1>
      </div>
      <div class="connection" role="status" aria-live="polite">
        <span :class="['status-dot', connectionTone]" aria-hidden="true"></span>
        {{ connectionLabel }}
      </div>
    </header>

    <section v-if="!runtimeReady" class="state-card" aria-live="polite">
      <h2>{{ shellMessages.loadingSecure }}</h2>
      <p>{{ runtimeReason }}</p>
      <router-link class="btn btn-secondary" to="/login">{{ shellMessages.backV1Login }}</router-link>
    </section>

    <section v-else-if="!snapshot.session" class="login-shell" aria-labelledby="v2-login-title">
      <form class="login-card" @submit.prevent="login">
        <span class="preview-badge">{{ shellMessages.isolatedTest }}</span>
        <h2 id="v2-login-title">{{ shellMessages.loginTitle }}</h2>
        <p class="muted">{{ shellMessages.credentialsMemory }}</p>

        <label for="v2-username">{{ shellMessages.userId }}</label>
        <input id="v2-username" v-model.trim="username" class="input" maxlength="128"
               autocomplete="username" required :disabled="authenticating" />

        <label for="v2-password">{{ shellMessages.password }}</label>
        <input id="v2-password" v-model="password" class="input" type="password"
               maxlength="1024" autocomplete="current-password" required
               :disabled="authenticating" />

        <p v-if="visibleFailure" class="error-msg" role="alert">{{ visibleFailure }}</p>
        <button class="btn btn-primary" type="submit" :disabled="!canAuthenticate">
          {{ authenticating ? shellMessages.authenticating : connectionReady ? shellMessages.login : shellMessages.connectingSecure }}
        </button>
        <router-link class="back-link" to="/login">{{ shellMessages.backStableV1 }}</router-link>
      </form>
    </section>

    <section v-else class="chat-shell">
      <nav class="conversation-panel" :aria-label="shellMessages.conversationNavigation">
        <div class="account-block">
          <strong>{{ snapshot.session.displayName }}</strong>
          <span>{{ snapshot.session.accountId }}</span>
          <button class="device-entry" type="button" aria-haspopup="dialog"
                  :aria-expanded="devicesOpen" @click="openDevices">
            {{ shellMessages.loginDevices }}
            <span v-if="snapshot.devices.length">{{ snapshot.devices.length }}</span>
          </button>
        </div>
        <ul class="conversation-list" :aria-label="shellMessages.availableConversations">
          <li v-for="conversation in snapshot.directory" :key="conversation.conversationId">
            <button :class="['conversation-button', { active: conversation.conversationId === snapshot.activeConversationId }]"
                    type="button"
                    :aria-current="conversation.conversationId === snapshot.activeConversationId ? 'page' : undefined"
                    @click="openConversation(conversation.conversationId)">
              <strong>{{ conversation.displayName }}</strong>
              <span>{{ conversation.kind === 'direct' ? shellMessages.direct : shellMessages.group }} · #{{ conversation.latestSequence }}</span>
            </button>
          </li>
        </ul>
        <p v-if="snapshot.directory.length === 0" class="empty-copy">{{ shellMessages.noConversations }}</p>
        <button v-if="snapshot.directoryHasMore" class="btn btn-text" type="button" @click="loadMoreDirectory">
          {{ shellMessages.loadMoreConversations }}
        </button>
      </nav>

      <section class="message-panel" :aria-label="shellMessages.messageRegion">
        <div v-if="!snapshot.activeConversationId" class="empty-state">
          <h2>{{ shellMessages.selectConversation }}</h2>
          <p>{{ shellMessages.cacheSync }}</p>
        </div>
        <template v-else>
          <div class="message-header">
            <strong>{{ activeConversationName }}</strong>
            <div class="message-header-actions">
              <span v-if="snapshot.historyLoading">{{ shellMessages.syncing }}</span>
              <button v-if="snapshot.searchEnabled" class="btn btn-text" type="button"
                      aria-controls="v2-message-search" :aria-expanded="searchOpen"
                      @click="toggleSearch">
                {{ searchOpen ? searchMessages.closeSearch : searchMessages.openSearch }}
              </button>
            </div>
          </div>
          <section v-if="snapshot.searchEnabled && searchOpen" id="v2-message-search"
                   class="message-search" aria-labelledby="v2-message-search-title">
            <form @submit.prevent="submitSearch">
              <label id="v2-message-search-title" for="v2-message-search-query">{{ searchMessages.searchConversation }}</label>
              <div>
                <input id="v2-message-search-query" v-model="searchDraft" class="input"
                       type="search" maxlength="128" autocomplete="off"
                       :placeholder="searchMessages.exactText" :disabled="snapshot.searchLoading" />
                <button class="btn btn-primary" type="submit"
                        :disabled="!searchDraft.trim() || snapshot.searchLoading">
                  {{ snapshot.searchLoading ? searchMessages.searching : searchMessages.search }}
                </button>
              </div>
            </form>
            <p class="search-status" aria-live="polite" aria-atomic="true">
              <template v-if="snapshot.searchFailure">{{ visibleSearchFailure }}</template>
              <template v-else-if="snapshot.searchContextLoading">{{ searchMessages.loadingContext }}</template>
              <template v-else-if="snapshot.searchQuery && !snapshot.searchLoading">
                {{ searchMessages.resultCount(snapshot.searchResults.length) }}
              </template>
            </p>
            <ol v-if="snapshot.searchResults.length" class="search-results"
                :aria-label="searchMessages.resultsLabel" :aria-busy="snapshot.searchLoading">
              <li v-for="hit in snapshot.searchResults" :key="hit.id">
                <button type="button" @click="locateSearchHit(hit)">
                  <span>{{ hit.content }}</span>
                  <small>#{{ hit.sequence }} · {{ formatDeviceTime(hit.acceptedAtEpochMs) }}</small>
                </button>
              </li>
            </ol>
            <button v-if="snapshot.searchHasMore" class="btn btn-text" type="button"
                    :disabled="snapshot.searchLoading" @click="loadMoreSearchResults">
              {{ searchMessages.loadMore }}
            </button>
          </section>
          <div class="message-timeline">
          <ol ref="messageListRef" class="message-list" role="log" aria-live="polite"
              tabindex="-1" @scroll="onMessageListScroll"
              :aria-busy="snapshot.historyLoading" :aria-label="v2TimelineMessages.history">
            <li v-for="message in snapshot.messages" :key="message.id || message.clientMessageId"
                :id="message.id ? `v2-message-${message.id}` : undefined" tabindex="-1"
                :class="['message-row', { mine: message.senderAccountId === snapshot.session.accountId, pinned: message.pinned }]">
              <div class="bubble">
                <span v-if="message.pinned" class="pin-badge" role="status">{{ v2TimelineMessages.pinned }}</span>
                <span v-if="message.forwarded" class="forwarded-badge">{{ v2TimelineMessages.forwarded }}</span>
                <div v-if="message.reply" class="reply-reference"
                     :aria-label="v2TimelineMessages.replyLabel(replyPreview(message))">
                  <strong>{{ v2TimelineMessages.reply }}</strong>
                  <span>{{ replyPreview(message) }}</span>
                </div>
                <p class="message-content">
                  <template v-for="(segment, index) in messageSegments(message)" :key="index">
                    <span v-if="segment.kind === 'mention'" class="message-mention"
                          :title="v2TimelineMessages.accountTitle(segment.targetAccountId)">{{ segment.text }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </p>
                <span v-if="message.contentRevision > 0" class="edited-badge">{{ v2TimelineMessages.edited }}</span>
                <form v-if="editingMessageId === message.id" class="edit-form"
                      @submit.prevent="submitEdit(message)">
                  <label :for="`edit-${message.id}`">{{ editMessages.formLabel }}</label>
                  <textarea :id="`edit-${message.id}`" :value="editDraft" class="input"
                            rows="3" required
                            @input="updateEditDraft"
                            @keydown.esc="cancelEditFromKeyboard"></textarea>
                  <small role="status" aria-live="polite" :aria-label="editMessages.bytes">
                    {{ editBudgetLabel }}
                  </small>
                  <div>
                    <button class="btn btn-text" type="button"
                            aria-controls="v2-mention-picker" aria-haspopup="dialog"
                            :aria-expanded="mentionPickerMode === 'edit'"
                            @click="openMentionPicker('edit', $event)">{{ v2ComposerMessages.mentionMember }}</button>
                    <button class="btn btn-primary" type="submit"
                            :disabled="!editDraft.trim() || !editBudget.withinBudget">{{ editMessages.save }}</button>
                    <button class="btn btn-text" type="button" :title="editMessages.cancelTitle"
                            @click="cancelEdit">{{ editMessages.cancel }}</button>
                  </div>
                </form>
                <div v-if="editCommand(message)" class="edit-state" aria-live="polite">
                  <p v-if="editCommand(message).deliveryState === 'sending'" role="status">{{ editMessages.saving }}</p>
                  <template v-else-if="editCommand(message).deliveryState === 'conflict'">
                    <p role="alert">{{ editMessages.conflict }}</p>
                    <small>{{ editMessages.serverVersion }}{{ message.content }}</small>
                    <div>
                      <button class="retry-link" type="button"
                              @click="rebaseEdit(editCommand(message).clientOperationId)">
                        {{ editMessages.rebase }}
                      </button>
                      <button class="btn btn-text" type="button"
                              @click="discardEdit(editCommand(message).clientOperationId)">{{ editMessages.discard }}</button>
                    </div>
                  </template>
                  <template v-else>
                    <p role="alert">{{ editMessages.saveFailed }}</p>
                    <button class="retry-link" type="button"
                            @click="retryEdit(editCommand(message).clientOperationId)">{{ editMessages.retry }}</button>
                    <button class="btn btn-text" type="button"
                            @click="discardEdit(editCommand(message).clientOperationId)">{{ editMessages.discard }}</button>
                  </template>
                </div>
                <div v-if="message.deliveryState === 'accepted' && message.availability === 'available'"
                     class="reaction-bar" role="group" :aria-label="reactionMessages.groupLabel(message.sequence)">
                  <button v-for="reaction in reactionChoices" :key="reaction.kind"
                          :class="['reaction-button', { active: reactionActive(message, reaction.kind) }]"
                          type="button" :aria-pressed="reactionActive(message, reaction.kind)"
                          :aria-label="reactionMessages.countLabel(reaction.label, reactionCount(message, reaction.kind))"
                          :disabled="reactionPending(message, reaction.kind)"
                          @click="toggleReaction(message, reaction.kind)">
                    <span aria-hidden="true">{{ reaction.emoji }}</span>
                    <small v-if="reactionCount(message, reaction.kind)">{{ reactionCount(message, reaction.kind) }}</small>
                  </button>
                </div>
                <button v-if="failedReaction(message)" class="retry-link" type="button"
                        :aria-label="reactionMessages.retryLabel(message.sequence)"
                        @click="retryReaction(failedReaction(message).clientOperationId)">
                  {{ reactionMessages.retry }}
                </button>
                <button v-if="message.deliveryState === 'accepted' && message.availability === 'available'"
                        class="pin-link" type="button" :aria-pressed="message.pinned"
                        :aria-label="pinMessages.actionLabel(message.pinned ? pinMessages.unpin : pinMessages.pin, message.sequence)"
                        :disabled="pinPending(message)" @click="togglePin(message)">
                  {{ message.pinned ? pinMessages.unpin : pinMessages.pin }}
                </button>
                <button v-if="failedPin(message)" class="retry-link" type="button"
                        :aria-label="pinMessages.retryLabel(message.sequence)"
                        @click="retryPin(failedPin(message).clientOperationId)">{{ pinMessages.retry }}</button>
                <span :aria-label="v2TimelineMessages.messageStatus(message.sequence, deliveryLabel(message.deliveryState))">
                  #{{ message.sequence }} · {{ deliveryLabel(message.deliveryState) }}
                </span>
                <button v-if="message.deliveryState === 'accepted' && message.availability === 'available'"
                        class="copy-link" type="button"
                        :aria-label="basicActionMessages.copyLabel(message.sequence)" @click="copyMessage(message)">
                  {{ basicActionMessages.copy }}
                </button>
                <button v-if="message.deliveryState === 'accepted' && message.availability === 'available'"
                        class="reply-link" type="button" :aria-label="basicActionMessages.replyLabel(message.sequence)"
                        @click="startReply(message)">
                  {{ basicActionMessages.reply }}
                </button>
                <button v-if="canEdit(message)" class="edit-link" type="button"
                        :aria-label="editMessages.editLabel(message.sequence)"
                        @click="startEdit(message)">{{ editMessages.edit }}</button>
                <button v-if="snapshot.forwardingEnabled && message.deliveryState === 'accepted'
                              && message.availability === 'available'"
                        class="forward-link" type="button" aria-haspopup="dialog"
                        :aria-label="forwardMessages.forwardLabel(message.sequence)"
                        @click="openForwardPicker(message)">{{ forwardMessages.forward }}</button>
                <button v-if="message.deliveryState === 'failed'" class="retry-link" type="button"
                        :aria-label="basicActionMessages.retryLabel"
                        @click="retryMessage(message.clientMessageId)">
                  {{ basicActionMessages.retry }}
                </button>
              </div>
            </li>
          </ol>
          <button v-if="pendingNewMessages" class="new-message-jump" type="button"
                  :aria-label="`${pendingNewMessagesLabel}${timelineMessages.backToLatestSuffix}`"
                  @click="revealNewMessages">
            {{ pendingNewMessagesLabel }} ↓
          </button>
          <p class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
            {{ pendingNewMessages ? `${pendingNewMessagesLabel}${timelineMessages.readingHistorySuffix}` : '' }}
          </p>
          </div>
          <form class="composer" @submit.prevent="sendMessage">
            <div v-if="replyTarget" class="composer-reply" role="status">
              <div>
                <strong>{{ v2ComposerMessages.replyingTo(replyTarget.sequence) }}</strong>
                <span>{{ replyTarget.content }}</span>
              </div>
              <button class="icon-button" type="button" :aria-label="v2ComposerMessages.cancelReply"
                      :title="v2ComposerMessages.cancelReplyTitle" @click="cancelReply">×</button>
            </div>
            <label class="visually-hidden" for="v2-message">{{ composerMessages.messageContent }}</label>
            <textarea id="v2-message" :value="draft" class="input" rows="2"
                      :placeholder="composerMessages.messagePlaceholder" @input="updateDraft"
                      @keydown.enter.exact.prevent="sendMessage"
                      @keydown.esc="cancelReplyFromKeyboard"></textarea>
            <small role="status" aria-live="polite" :aria-label="composerMessages.messageBytes">
              {{ draftBudgetLabel }}
            </small>
            <button class="btn btn-text" type="button"
                    aria-controls="v2-mention-picker" aria-haspopup="dialog"
                    :aria-expanded="mentionPickerMode === 'draft'"
                    @click="openMentionPicker('draft', $event)">{{ v2ComposerMessages.mentionMember }}</button>
            <button class="btn btn-primary" type="submit" :title="v2ComposerMessages.sendTitle"
                    :disabled="!draft.trim() || !draftBudget.withinBudget">{{ composerMessages.send }}</button>
          </form>
          <section v-if="mentionPickerOpen" id="v2-mention-picker" ref="mentionPickerRef"
                   class="mention-picker" role="dialog"
                   aria-modal="false" aria-labelledby="mention-picker-title"
                   @keydown.esc="closeMentionPicker()">
            <header>
              <strong id="mention-picker-title">{{ mentionMessages.title }}</strong>
              <button class="icon-button" type="button" data-mention-close
                      :aria-label="mentionMessages.close"
                      @click="closeMentionPicker()">×</button>
            </header>
            <p v-if="snapshot.participantFailure" role="alert">
              {{ visibleParticipantFailure }}
              <button class="retry-link" type="button" @click="refreshParticipants">{{ mentionMessages.retry }}</button>
            </p>
            <ul ref="mentionListRef" role="listbox" :aria-label="mentionMessages.members"
                :aria-busy="snapshot.participantsLoading" @keydown="onMentionListKeydown">
              <li v-for="participant in snapshot.participants" :key="participant.accountId">
                <button type="button" role="option" aria-selected="false"
                        @click="chooseMention(participant)">
                  <strong>{{ participant.displayName }}</strong>
                  <span>{{ participant.role === 'owner' ? mentionMessages.owner : participant.role === 'admin' ? mentionMessages.admin : mentionMessages.member }}</span>
                </button>
              </li>
            </ul>
            <p v-if="snapshot.participantsLoading" role="status">{{ mentionMessages.loading }}</p>
            <button v-if="snapshot.participantsHasMore" class="btn btn-text" type="button"
                    @click="loadMoreParticipants">{{ mentionMessages.loadMore }}</button>
          </section>
          <div v-if="forwardSource" class="dialog-backdrop" @click.self="closeForwardDialog">
            <section ref="forwardDialogRef" class="forward-dialog" role="dialog" aria-modal="true"
                     aria-labelledby="forward-dialog-title"
                     aria-describedby="forward-dialog-description" tabindex="-1"
                     @keydown="onForwardDialogKeydown">
              <header>
                <div>
                  <h2 id="forward-dialog-title">{{ forwardMessages.title }}</h2>
                  <p id="forward-dialog-description">{{ forwardMessages.description }}</p>
                </div>
                <button id="forward-dialog-close" class="icon-button" type="button"
                        :aria-label="forwardMessages.close" @click="closeForwardDialog">×</button>
              </header>
              <ul role="listbox" :aria-label="forwardMessages.targets" :aria-busy="forwardPending">
                <li v-for="conversation in snapshot.directory" :key="conversation.conversationId">
                  <button type="button" role="option" :disabled="forwardPending"
                          @click="chooseForwardTarget(conversation)">
                    <strong>{{ conversation.displayName }}</strong>
                    <span>{{ conversation.kind === 'direct' ? forwardMessages.direct : forwardMessages.group }}</span>
                  </button>
                </li>
              </ul>
              <p v-if="forwardPending" role="status">{{ forwardMessages.forwarding }}</p>
            </section>
          </div>
        </template>
        <p v-if="actionError" class="action-error" role="alert">{{ actionError }}</p>
        <p class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">
          {{ copyAnnouncement }}
        </p>
      </section>
    </section>

    <div v-if="devicesOpen" class="dialog-backdrop" @click.self="closeDeviceDialog">
      <section ref="deviceDialogRef" class="device-dialog" role="dialog" aria-modal="true"
               aria-labelledby="device-dialog-title" aria-describedby="device-dialog-description"
               tabindex="-1" @keydown="onDeviceDialogKeydown">
        <header class="device-dialog-header">
          <div>
            <h2 id="device-dialog-title">{{ deviceMessages.title }}</h2>
            <p id="device-dialog-description">{{ deviceMessages.description }}</p>
          </div>
          <button id="device-dialog-close" class="icon-button" type="button"
                  :aria-label="deviceMessages.close" @click="closeDeviceDialog">×</button>
        </header>
        <p v-if="snapshot.deviceFailure" class="error-msg" role="alert">
          {{ visibleDeviceFailure }}
          <button class="retry-link" type="button" :disabled="!canManageDevices" @click="refreshDevices">{{ deviceMessages.retry }}</button>
        </p>
        <p v-if="!canManageDevices" class="device-notice" role="status">{{ deviceMessages.reconnectNotice }}</p>
        <ul class="device-list" :aria-busy="snapshot.devicesLoading">
          <li v-for="device in snapshot.devices" :key="device.deviceId" class="device-row">
            <div class="device-icon" aria-hidden="true">{{ device.platform === 'windows' ? '▣' : '◎' }}</div>
            <div class="device-copy">
              <strong>{{ device.platform === 'windows' ? deviceMessages.windows : deviceMessages.web }}</strong>
              <span>{{ device.current ? deviceMessages.currentDevice : deviceMessages.recentActivity(formatDeviceTime(device.lastSeenAtEpochMs)) }}</span>
              <small>{{ shortDeviceId(device.deviceId) }}</small>
            </div>
            <span v-if="device.current" class="current-device">{{ deviceMessages.current }}</span>
            <button v-else-if="confirmingDeviceId !== device.deviceId" class="btn btn-danger-outline"
                    type="button" :disabled="!canManageDevices || Boolean(snapshot.revokingDeviceId)"
                    @click="confirmingDeviceId = device.deviceId">{{ deviceMessages.revoke }}</button>
            <div v-else class="revoke-confirm" role="group" :aria-label="deviceMessages.confirmGroup">
              <span>{{ deviceMessages.revokeAll }}</span>
              <button class="btn btn-danger" type="button"
                      :disabled="snapshot.revokingDeviceId === device.deviceId"
                      @click="revokeDevice(device.deviceId)">
                {{ snapshot.revokingDeviceId === device.deviceId ? deviceMessages.revoking : deviceMessages.confirm }}
              </button>
              <button class="btn btn-text" type="button" :disabled="Boolean(snapshot.revokingDeviceId)"
                      @click="confirmingDeviceId = null">{{ deviceMessages.cancel }}</button>
            </div>
          </li>
        </ul>
        <p v-if="snapshot.devicesLoading && snapshot.devices.length === 0" class="empty-copy" role="status">{{ deviceMessages.loading }}</p>
        <footer class="device-dialog-footer">
          <button class="btn btn-secondary" type="button" :disabled="!canManageDevices || snapshot.devicesLoading"
                  @click="refreshDevices">{{ deviceMessages.refresh }}</button>
          <button class="btn btn-primary" type="button" @click="closeDeviceDialog">{{ deviceMessages.done }}</button>
        </footer>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { V2_RUNTIME_KEY } from '../application/v2RuntimeKey'
import { MessageReactionKind } from '../protocol/v2/generated/messaging_pb'
import { messageTextBudget } from '../messaging/messageTextBudget.js'
import { copyMessageText } from '../messaging/copyMessageText.js'
import { addPendingNewMessages } from '../messaging/newMessageIndicator.js'
import { classifyV2TailUpdate } from '../messaging/v2TailActivity'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { useUserStore } from '../stores/user'
import {
  messageTimelineMessages,
  composerMessages as composerCatalogMessages,
  localizeV2PreviewDeviceFailure,
  localizeV2PreviewParticipantFailure,
  localizeV2PreviewSearchFailure,
  v2PreviewBasicActionMessages,
  v2PreviewComposerMessages,
  v2PreviewDeviceMessages,
  v2PreviewEditMessages,
  v2PreviewForwardMessages,
  v2PreviewMentionMessages,
  v2PreviewPinMessages,
  v2PreviewReactionMessages,
  v2PreviewSearchMessages,
  v2PreviewShellMessages,
  v2PreviewTimelineMessages,
} from '../localization/webLocale'
import {
  anchorsFromMentionSpans,
  insertMention,
  reconcileMentionEdit,
  segmentMentionText,
  serializeMentionAnchors
} from '../application/v2MentionComposer'

const runtimeRef = inject(V2_RUNTIME_KEY)
const userStore = useUserStore()
const shellMessages = computed(() => v2PreviewShellMessages(userStore.locale))
const searchMessages = computed(() => v2PreviewSearchMessages(userStore.locale))
const timelineMessages = computed(() => messageTimelineMessages(userStore.locale))
const v2TimelineMessages = computed(() => v2PreviewTimelineMessages(userStore.locale))
const basicActionMessages = computed(() => v2PreviewBasicActionMessages(userStore.locale))
const composerMessages = computed(() => composerCatalogMessages(userStore.locale))
const v2ComposerMessages = computed(() => v2PreviewComposerMessages(userStore.locale))
const deviceMessages = computed(() => v2PreviewDeviceMessages(userStore.locale))
const editMessages = computed(() => v2PreviewEditMessages(userStore.locale))
const mentionMessages = computed(() => v2PreviewMentionMessages(userStore.locale))
const pinMessages = computed(() => v2PreviewPinMessages(userStore.locale))
const reactionMessages = computed(() => v2PreviewReactionMessages(userStore.locale))
const forwardMessages = computed(() => v2PreviewForwardMessages(userStore.locale))
const username = ref('')
const password = ref('')
const draft = ref('')
const searchDraft = ref('')
const searchOpen = ref(false)
const actionError = ref('')
const copyAnnouncement = ref('')
const messageListRef = ref(null)
const followingMessageTail = ref(true)
const pendingNewMessages = ref(0)
const pendingNewMessagesLabel = computed(() => pendingNewMessages.value > 0
  ? `${pendingNewMessages.value >= 99 ? '99+' : pendingNewMessages.value}${timelineMessages.value.newMessagesSuffix}`
  : '')
const replyTarget = ref(null)
const editingMessageId = ref(null)
const editDraft = ref('')
const draftBudget = computed(() => messageTextBudget(draft.value))
const draftBudgetLabel = computed(() => draftBudget.value.withinBudget
  ? `${draftBudget.value.bytes} / ${draftBudget.value.maximum} ${composerMessages.value.bytes}`
  : `${composerMessages.value.overLimitPrefix}${draftBudget.value.overage} ${composerMessages.value.bytes}`
    + `${composerMessages.value.maximumPrefix}${draftBudget.value.maximum}${composerMessages.value.maximumSuffix}`)
const editBudget = computed(() => messageTextBudget(editDraft.value))
const editBudgetLabel = computed(() => editBudget.value.withinBudget
  ? `${editBudget.value.bytes} / ${editBudget.value.maximum} ${composerMessages.value.bytes}`
  : `${composerMessages.value.overLimitPrefix}${editBudget.value.overage} ${composerMessages.value.bytes}`
    + `${composerMessages.value.maximumPrefix}${editBudget.value.maximum}${composerMessages.value.maximumSuffix}`)
const draftMentionAnchors = ref([])
const editMentionAnchors = ref([])
const mentionPickerMode = ref(null)
const mentionPickerRef = ref(null)
const mentionListRef = ref(null)
let mentionPickerTrigger = null
const forwardSource = ref(null)
const forwardPending = ref(false)
const authenticationPending = ref(false)
const devicesOpen = ref(false)
const confirmingDeviceId = ref(null)
const forwardDialogActive = computed(() => Boolean(forwardSource.value))
const {
  dialogRef: forwardDialogRef,
  closeDialog: closeForwardDialog,
  onDialogKeydown: onForwardDialogKeydown,
} = useModalKeyboardBoundary({
  onClose: closeForwardPicker,
  canClose: () => !forwardPending.value,
  initialFocusSelector: '#forward-dialog-close',
  active: forwardDialogActive,
})
const {
  dialogRef: deviceDialogRef,
  closeDialog: closeDeviceDialog,
  onDialogKeydown: onDeviceDialogKeydown,
} = useModalKeyboardBoundary({
  onClose: closeDevices,
  initialFocusSelector: '#device-dialog-close',
  active: devicesOpen,
})
const snapshot = ref({
  connectionState: 'idle', session: null, directory: [], directoryHasMore: false,
  activeConversationId: null, messages: [], reactionCommands: [], pinCommands: [], editCommands: [],
  participants: [], participantsLoading: false, participantsHasMore: false, participantFailure: '',
  historyLoading: false, devices: [],
  devicesLoading: false, revokingDeviceId: null, deviceFailure: '', lastFailure: '',
  forwardingEnabled: false, searchEnabled: false, searchQuery: '', searchResults: [],
  searchLoading: false, searchHasMore: false, searchFailure: '', searchContextLoading: false
})
let unsubscribe = null
let startedApplication = null

const runtimeReady = computed(() => runtimeRef?.value?.enabled === true)
const runtimeReason = computed(() => runtimeRef?.value?.reason || shellMessages.value.runtimeUnavailable)
const connectionReady = computed(() => snapshot.value.connectionState === 'connected')
const authenticating = computed(() => authenticationPending.value
  || ['connecting', 'negotiating', 'resuming'].includes(snapshot.value.connectionState))
const canAuthenticate = computed(() => Boolean(connectionReady.value && username.value && password.value && !authenticating.value))
const visibleFailure = computed(() => actionError.value || snapshot.value.lastFailure)
const visibleSearchFailure = computed(() => localizeV2PreviewSearchFailure(
  userStore.locale, snapshot.value.searchFailure))
const visibleParticipantFailure = computed(() => localizeV2PreviewParticipantFailure(
  userStore.locale, snapshot.value.participantFailure))
const visibleDeviceFailure = computed(() => localizeV2PreviewDeviceFailure(
  userStore.locale, snapshot.value.deviceFailure))
const activeConversationName = computed(() => snapshot.value.directory.find(
  item => item.conversationId === snapshot.value.activeConversationId
)?.displayName || shellMessages.value.conversation)
const connectionLabel = computed(() => ({
  idle: shellMessages.value.idle, connecting: shellMessages.value.connecting,
  negotiating: shellMessages.value.negotiating, connected: shellMessages.value.connected,
  resuming: shellMessages.value.resuming, authenticated: shellMessages.value.authenticated,
  offline: shellMessages.value.offline, 'reconnect-wait': shellMessages.value.reconnectWait,
  stopped: shellMessages.value.stopped,
}[snapshot.value.connectionState] || shellMessages.value.unknownState))
const connectionTone = computed(() => snapshot.value.connectionState === 'authenticated'
  ? 'ok' : ['offline', 'reconnect-wait'].includes(snapshot.value.connectionState) ? 'warn' : '')
const canManageDevices = computed(() => snapshot.value.connectionState === 'authenticated')
const reactionChoices = computed(() => [
  { kind: MessageReactionKind.LIKE, emoji: '👍', label: reactionMessages.value.like },
  { kind: MessageReactionKind.LOVE, emoji: '❤️', label: reactionMessages.value.love },
  { kind: MessageReactionKind.LAUGH, emoji: '😂', label: reactionMessages.value.laugh },
  { kind: MessageReactionKind.SURPRISED, emoji: '😮', label: reactionMessages.value.surprised },
  { kind: MessageReactionKind.SAD, emoji: '😢', label: reactionMessages.value.sad },
  { kind: MessageReactionKind.ANGRY, emoji: '😠', label: reactionMessages.value.angry },
])

function attachRuntime(runtime) {
  unsubscribe?.()
  unsubscribe = null
  if (!runtime?.enabled || runtime.application === startedApplication) return
  startedApplication = runtime.application
  unsubscribe = runtime.application.subscribe(next => {
    const previous = snapshot.value
    const tailUpdate = classifyV2TailUpdate(previous, next)
    snapshot.value = next
    nextTick(() => {
      if (snapshot.value.activeConversationId !== next.activeConversationId) return
      if (tailUpdate.conversationChanged) {
        followingMessageTail.value = true
        pendingNewMessages.value = 0
        scrollMessageListToTail()
      } else if (tailUpdate.additions > 0) {
        if (followingMessageTail.value) scrollMessageListToTail()
        else pendingNewMessages.value = addPendingNewMessages(
          pendingNewMessages.value, tailUpdate.additions)
      }
    })
    if (next.session || next.lastFailure || next.connectionState !== 'connected') authenticationPending.value = false
  })
  runtime.application.start()
}

function onMessageListScroll() {
  const element = messageListRef.value
  if (!element) return
  followingMessageTail.value = element.scrollHeight - element.scrollTop - element.clientHeight < 80
  if (followingMessageTail.value) pendingNewMessages.value = 0
}

function scrollMessageListToTail() {
  nextTick(() => {
    const element = messageListRef.value
    if (!element) return
    element.scrollTop = element.scrollHeight
    followingMessageTail.value = true
    pendingNewMessages.value = 0
  })
}

function revealNewMessages() {
  pendingNewMessages.value = 0
  scrollMessageListToTail()
  nextTick(() => messageListRef.value?.focus({ preventScroll: true }))
}

function login() {
  if (!canAuthenticate.value || !runtimeRef.value.enabled) return
  actionError.value = ''
  const passwordBytes = new TextEncoder().encode(password.value)
  password.value = ''
  authenticationPending.value = true
  try {
    runtimeRef.value.application.authenticate(username.value, passwordBytes)
  } catch (error) {
    authenticationPending.value = false
    actionError.value = error instanceof Error ? error.message : shellMessages.value.authStartFailed
  } finally {
    passwordBytes.fill(0)
  }
}

async function openConversation(conversationId) {
  actionError.value = ''
  replyTarget.value = null
  cancelEdit()
  closeMentionPicker(false)
  closeForwardPicker()
  draftMentionAnchors.value = []
  searchOpen.value = false
  searchDraft.value = ''
  try { await runtimeRef.value.application.openConversation(conversationId) }
  catch (error) { actionError.value = error instanceof Error ? error.message : shellMessages.value.openConversationFailed }
}

function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (!searchOpen.value) {
    searchDraft.value = ''
    runtimeRef.value.application.clearSearch()
  } else {
    nextTick(() => document.getElementById('v2-message-search-query')?.focus())
  }
}

function submitSearch() {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.searchMessages(searchDraft.value)) {
      actionError.value = searchMessages.value.invalidQuery
    }
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : searchMessages.value.searchFailed
  }
}

function loadMoreSearchResults() {
  try { runtimeRef.value.application.loadMoreSearchResults() }
  catch (error) { actionError.value = error instanceof Error ? error.message : searchMessages.value.loadMoreFailed }
}

function locateSearchHit(hit) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.revealSearchHit(hit.id)) {
      actionError.value = searchMessages.value.contextUnavailable
      return
    }
    nextTick(() => {
      const element = document.getElementById(`v2-message-${hit.id}`)
      element?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      element?.focus({ preventScroll: true })
    })
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : searchMessages.value.openResultFailed
  }
}

function loadMoreDirectory() {
  try { runtimeRef.value.application.loadMoreDirectory() }
  catch (error) { actionError.value = error instanceof Error ? error.message : shellMessages.value.loadConversationsFailed }
}

function sendMessage() {
  const text = draft.value
  if (!text.trim() || !draftBudget.value.withinBudget) return
  actionError.value = ''
  try {
    const mentions = serializeMentionAnchors(text, draftMentionAnchors.value)
    if (replyTarget.value) {
      runtimeRef.value.application.sendReply(replyTarget.value.id, text, mentions)
    } else {
      runtimeRef.value.application.sendText(text, mentions)
    }
    draft.value = ''
    draftMentionAnchors.value = []
    closeMentionPicker(false)
    replyTarget.value = null
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : v2ComposerMessages.value.sendFailed
  }
}

function startReply(message) {
  if (!message?.id || message.deliveryState !== 'accepted' || message.availability !== 'available') return
  replyTarget.value = { id: message.id, sequence: message.sequence, content: message.content }
  nextTick(() => document.getElementById('v2-message')?.focus())
}

function cancelReply() {
  replyTarget.value = null
}

function cancelReplyFromKeyboard(event) {
  if (!replyTarget.value) return
  event.preventDefault()
  cancelReply()
}

async function copyMessage(message) {
  if (message?.deliveryState !== 'accepted' || message.availability !== 'available'
      || typeof message.content !== 'string' || message.content.length === 0) return
  actionError.value = ''
  copyAnnouncement.value = ''
  await nextTick()
  if (await copyMessageText(message.content)) {
    copyAnnouncement.value = basicActionMessages.value.copied(message.sequence)
  } else {
    actionError.value = basicActionMessages.value.copyFailed
  }
}

function replyPreview(message) {
  const target = snapshot.value.messages.find(item => item.id === message.reply?.targetMessageId)
  if (!target) return v2TimelineMessages.value.originalUnavailable
  return target.availability === 'recalled' ? v2TimelineMessages.value.originalRecalled : target.content
}

function retryMessage(clientMessageId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.retryMessage(clientMessageId)) actionError.value = basicActionMessages.value.retryUnavailable
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : basicActionMessages.value.retryFailed
  }
}

function openForwardPicker(message) {
  if (!snapshot.value.forwardingEnabled || !message?.id
      || message.deliveryState !== 'accepted' || message.availability !== 'available') return
  forwardSource.value = { id: message.id, content: message.content }
  forwardPending.value = false
}

function closeForwardPicker() {
  if (forwardPending.value) return
  forwardSource.value = null
}

async function chooseForwardTarget(conversation) {
  if (!forwardSource.value || forwardPending.value) return
  actionError.value = ''
  forwardPending.value = true
  try {
    const result = await runtimeRef.value.application.forwardMessage(
      forwardSource.value.id, conversation.conversationId)
    if (result.deliveryState !== 'sending') {
      actionError.value = result.errorCode === 'CACHE_UNAVAILABLE'
        ? forwardMessages.value.cacheUnavailable
        : forwardMessages.value.retryInTarget
    }
    forwardPending.value = false
    forwardSource.value = null
  } catch (error) {
    forwardPending.value = false
    actionError.value = error instanceof Error ? error.message : forwardMessages.value.failed
  }
}

function reactionCount(message, reaction) {
  return message.reactions?.find(item => item.reaction === reaction)?.actorAccountIds.length || 0
}

function reactionActive(message, reaction) {
  return message.reactions?.some(item => item.reaction === reaction
    && item.actorAccountIds.includes(snapshot.value.session?.accountId)) || false
}

function reactionPending(message, reaction) {
  return snapshot.value.reactionCommands.some(command => command.messageId === message.id
    && command.reaction === reaction && command.deliveryState === 'sending')
}

function failedReaction(message) {
  return snapshot.value.reactionCommands.find(command => command.messageId === message.id
    && command.deliveryState === 'failed') || null
}

function toggleReaction(message, reaction) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.setReaction(message.id, reaction)) {
      actionError.value = reactionMessages.value.unavailable
    }
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : reactionMessages.value.failed
  }
}

function retryReaction(clientOperationId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.retryReaction(clientOperationId)) {
      actionError.value = reactionMessages.value.retryUnavailable
    }
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : reactionMessages.value.retryFailed
  }
}

function pinPending(message) {
  return snapshot.value.pinCommands.some(command => command.messageId === message.id
    && command.deliveryState === 'sending')
}
function failedPin(message) {
  return snapshot.value.pinCommands.find(command => command.messageId === message.id
    && command.deliveryState === 'failed') || null
}
function togglePin(message) {
  actionError.value = ''
  try { if (!runtimeRef.value.application.setPin(message.id)) actionError.value = pinMessages.value.unavailable }
  catch (error) { actionError.value = error instanceof Error ? error.message : pinMessages.value.failed }
}
function retryPin(operationId) {
  actionError.value = ''
  try { if (!runtimeRef.value.application.retryPin(operationId)) actionError.value = pinMessages.value.retryUnavailable }
  catch (error) { actionError.value = error instanceof Error ? error.message : pinMessages.value.retryFailed }
}

function editCommand(message) {
  return snapshot.value.editCommands.find(command => command.messageId === message.id) || null
}

function visibleMessageContent(message) {
  return editCommand(message)?.proposedContent || message.content
}

function canEdit(message) {
  return message.senderAccountId === snapshot.value.session?.accountId
    && message.deliveryState === 'accepted' && message.availability === 'available'
    && !editCommand(message) && editingMessageId.value !== message.id
}

function startEdit(message) {
  if (!canEdit(message)) return
  editingMessageId.value = message.id
  editDraft.value = message.content
  try { editMentionAnchors.value = anchorsFromMentionSpans(message.content, message.mentions || []) }
  catch { editMentionAnchors.value = [] }
  nextTick(() => document.getElementById(`edit-${message.id}`)?.focus())
}

function cancelEdit() {
  editingMessageId.value = null
  editDraft.value = ''
  editMentionAnchors.value = []
  if (mentionPickerMode.value === 'edit') closeMentionPicker(false)
}

function cancelEditFromKeyboard(event) {
  if (!editingMessageId.value) return
  event.preventDefault()
  cancelEdit()
}

function submitEdit(message) {
  const text = editDraft.value
  if (!text.trim() || !editBudget.value.withinBudget) return
  actionError.value = ''
  try {
    const mentions = serializeMentionAnchors(text, editMentionAnchors.value)
    if (!runtimeRef.value.application.editMessage(message.id, text, mentions)) {
      actionError.value = editMessages.value.unavailable
      return
    }
    cancelEdit()
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : editMessages.value.failed
  }
}

function updateDraft(event) {
  const next = event.target.value
  draftMentionAnchors.value = reconcileMentionEdit(draft.value, next, draftMentionAnchors.value)
  draft.value = next
}

function updateEditDraft(event) {
  const next = event.target.value
  editMentionAnchors.value = reconcileMentionEdit(editDraft.value, next, editMentionAnchors.value)
  editDraft.value = next
}

function openMentionPicker(mode, event) {
  mentionPickerTrigger = event?.currentTarget || null
  mentionPickerMode.value = mode
  if (snapshot.value.participants.length === 0) refreshParticipants()
  nextTick(() => {
    const firstOption = mentionListRef.value?.querySelector('[role="option"]')
    ;(firstOption || mentionPickerRef.value?.querySelector('[data-mention-close]'))?.focus()
  })
}

function closeMentionPicker(restoreFocus = true) {
  mentionPickerMode.value = null
  const trigger = mentionPickerTrigger
  mentionPickerTrigger = null
  if (restoreFocus) nextTick(() => trigger?.focus())
}

const mentionPickerOpen = computed(() => Boolean(mentionPickerMode.value))

function refreshParticipants() {
  try { runtimeRef.value.application.refreshParticipants() }
  catch (error) { actionError.value = error instanceof Error ? error.message : mentionMessages.value.loadFailed }
}

function loadMoreParticipants() {
  try { runtimeRef.value.application.loadMoreParticipants() }
  catch (error) { actionError.value = error instanceof Error ? error.message : mentionMessages.value.loadMoreFailed }
}

function onMentionListKeydown(event) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const options = Array.from(mentionListRef.value?.querySelectorAll('[role="option"]') || [])
  if (options.length === 0) return
  event.preventDefault()
  const current = options.indexOf(document.activeElement)
  let next = current
  if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = options.length - 1
  else if (event.key === 'ArrowDown') next = (current + 1 + options.length) % options.length
  else next = current <= 0 ? options.length - 1 : current - 1
  options[next]?.focus()
}

function chooseMention(participant) {
  const edit = mentionPickerMode.value === 'edit'
  const element = document.getElementById(edit ? `edit-${editingMessageId.value}` : 'v2-message')
  const text = edit ? editDraft.value : draft.value
  const anchors = edit ? editMentionAnchors.value : draftMentionAnchors.value
  const start = element?.selectionStart ?? text.length
  const end = element?.selectionEnd ?? start
  try {
    const next = insertMention(text, anchors, start, end, participant)
    if (edit) { editDraft.value = next.text; editMentionAnchors.value = next.anchors }
    else { draft.value = next.text; draftMentionAnchors.value = next.anchors }
    closeMentionPicker(false)
    nextTick(() => {
      element?.focus()
      element?.setSelectionRange(next.caretUtf16, next.caretUtf16)
    })
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : mentionMessages.value.insertFailed
  }
}

function messageSegments(message) {
  const command = editCommand(message)
  return segmentMentionText(
    command?.proposedContent || message.content,
    command?.proposedMentions || message.mentions || []
  )
}

function retryEdit(operationId) {
  actionError.value = ''
  try { if (!runtimeRef.value.application.retryEdit(operationId)) actionError.value = editMessages.value.retryUnavailable }
  catch (error) { actionError.value = error instanceof Error ? error.message : editMessages.value.retryFailed }
}

function rebaseEdit(operationId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.rebaseEdit(operationId)) {
      actionError.value = editMessages.value.rebaseUnavailable
    }
  } catch (error) { actionError.value = error instanceof Error ? error.message : editMessages.value.retryFailed }
}

function discardEdit(operationId) {
  actionError.value = ''
  try { if (!runtimeRef.value.application.discardEdit(operationId)) actionError.value = editMessages.value.discardMissing }
  catch (error) { actionError.value = error instanceof Error ? error.message : editMessages.value.discardFailed }
}

function openDevices() {
  devicesOpen.value = true
  confirmingDeviceId.value = null
  if (snapshot.value.devices.length === 0 && canManageDevices.value) refreshDevices()
}

function closeDevices() {
  devicesOpen.value = false
  confirmingDeviceId.value = null
}

function refreshDevices() {
  actionError.value = ''
  try { runtimeRef.value.application.refreshDevices() }
  catch (error) { actionError.value = error instanceof Error ? error.message : deviceMessages.value.refreshFailed }
}

function revokeDevice(deviceId) {
  actionError.value = ''
  try {
    if (!runtimeRef.value.application.revokeDevice(deviceId)) actionError.value = deviceMessages.value.revokeUnavailable
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : deviceMessages.value.revokeFailed
  }
}

function shortDeviceId(deviceId) {
  return `${deviceId.slice(0, 8)}…${deviceId.slice(-4)}`
}

function formatDeviceTime(epochMs) {
  return new Intl.DateTimeFormat(userStore.locale, { dateStyle: 'medium', timeStyle: 'short' }).format(epochMs)
}

function deliveryLabel(state) {
  return state === 'accepted' ? v2TimelineMessages.value.accepted
    : state === 'sending' ? v2TimelineMessages.value.sending : v2TimelineMessages.value.failed
}

onMounted(() => attachRuntime(runtimeRef?.value))
const stopRuntimeWatch = watch(() => runtimeRef?.value, attachRuntime)
onUnmounted(() => {
  stopRuntimeWatch()
  unsubscribe?.()
  if (startedApplication) {
    try { startedApplication.stop() } catch { /* page disposal may already own shutdown */ }
  }
})
</script>

<style scoped>
.v2-preview { height: 100%; display: flex; flex-direction: column; background: var(--bg-primary); color: var(--text-primary); }
.preview-header { min-height: 72px; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.preview-header h1 { font-size: 20px; }
.preview-badge { display: inline-block; color: var(--accent); font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.connection { display: flex; gap: 8px; align-items: center; color: var(--text-secondary); }
.status-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--text-tertiary); }
.status-dot.ok { background: var(--success); }.status-dot.warn { background: var(--warning); }
.state-card, .login-shell { flex: 1; display: grid; place-content: center; gap: 16px; padding: 24px; text-align: center; }
.login-card { width: min(420px, calc(100vw - 32px)); display: grid; gap: 12px; padding: 32px; text-align: left; border-radius: 16px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.login-card h2 { font-size: 24px; }.muted, .empty-copy { color: var(--text-secondary); }.error-msg, .action-error { color: var(--danger); }
.back-link { text-align: center; color: var(--text-link); text-decoration: none; }
.chat-shell { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(220px, 300px) 1fr; }
.conversation-panel { overflow-y: auto; border-right: 1px solid var(--border-color); background: var(--bg-secondary); }
.conversation-list { margin: 0; padding: 0; list-style: none; }
.account-block { display: grid; gap: 4px; padding: 18px; border-bottom: 1px solid var(--border-color); }
.account-block span { overflow: hidden; color: var(--text-secondary); font-size: 11px; text-overflow: ellipsis; }
.device-entry { margin-top: 8px; padding: 7px 9px; display: flex; justify-content: space-between; border: 1px solid var(--border-color); border-radius: 8px; color: var(--text-primary); background: var(--bg-primary); cursor: pointer; }
.device-entry:hover { background: var(--bg-hover); }.device-entry span { font-size: 12px; }
.conversation-button { width: 100%; display: grid; gap: 4px; padding: 14px 18px; border: 0; border-bottom: 1px solid var(--border-light); text-align: left; color: var(--text-primary); background: transparent; cursor: pointer; }
.conversation-button:hover { background: var(--bg-hover); }.conversation-button.active { background: var(--bg-active); }
.reply-reference { margin-bottom: 6px; padding: 6px 8px; display: grid; gap: 2px; border-left: 3px solid var(--accent); border-radius: 4px; color: var(--text-secondary); background: var(--bg-primary); font-size: 12px; }
.reply-reference span, .composer-reply span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.reply-link, .copy-link { margin-left: 8px; border: 0; color: var(--text-link); background: transparent; cursor: pointer; }
.edit-link { margin-left: 8px; border: 0; color: var(--text-link); background: transparent; cursor: pointer; }
.forward-link { margin-left: 8px; border: 0; color: var(--text-link); background: transparent; cursor: pointer; }
.edited-badge { margin-left: 6px; }.forwarded-badge { margin-right: 6px; color: var(--accent); font-weight: 600; }
.edit-form, .edit-state { margin-top: 8px; padding: 8px; display: grid; gap: 8px; border-radius: 8px; background: var(--bg-primary); }
.edit-form label { font-size: 12px; font-weight: 600; }.edit-form textarea { width: 100%; resize: vertical; }
.edit-form > div, .edit-state > div { display: flex; align-items: center; gap: 8px; }
.edit-state p { margin: 0; font-size: 12px; }.edit-state small { color: var(--text-secondary); overflow-wrap: anywhere; }
.composer-reply { flex: 1 0 100%; padding: 8px 10px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-left: 3px solid var(--accent); border-radius: 6px; background: var(--bg-primary); }
.composer-reply > div { min-width: 0; display: grid; gap: 2px; }
.conversation-button span { color: var(--text-secondary); font-size: 12px; }.empty-copy { padding: 20px; }
.message-panel { min-width: 0; min-height: 0; display: flex; flex-direction: column; position: relative; }
.message-header { padding: 16px 20px; display: flex; justify-content: space-between; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.message-header-actions { display: flex; align-items: center; gap: 10px; }
.message-search { padding: 12px 20px; display: grid; gap: 8px; border-bottom: 1px solid var(--border-color); background: var(--bg-secondary); }
.message-search form { display: grid; gap: 6px; }.message-search form > div { display: flex; gap: 8px; }.message-search input { min-width: 0; flex: 1; }
.search-status { min-height: 20px; color: var(--text-secondary); font-size: 12px; }
.search-results { max-height: 240px; overflow-y: auto; display: grid; gap: 4px; list-style: none; }
.search-results button { width: 100%; padding: 9px 10px; display: grid; gap: 3px; border: 0; border-radius: 8px; text-align: left; color: var(--text-primary); background: var(--bg-primary); cursor: pointer; }
.search-results button:hover, .search-results button:focus-visible { background: var(--bg-hover); }.search-results span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.search-results small { color: var(--text-secondary); }
.message-timeline { position: relative; flex: 1; min-height: 0; }
.message-list { height: 100%; box-sizing: border-box; overflow-y: auto; padding: 20px; list-style: none; }
.new-message-jump { position: absolute; right: 20px; bottom: 14px; z-index: 4; padding: 8px 14px; border: 1px solid var(--border-color); border-radius: 999px; color: var(--text-link); background: var(--bg-secondary); box-shadow: var(--shadow-md); cursor: pointer; }
.new-message-jump:hover, .new-message-jump:focus-visible { background: var(--bg-hover); }
.message-row:focus { outline: 2px solid var(--accent); outline-offset: 3px; }
.message-row { display: flex; margin-bottom: 12px; }.message-row.mine { justify-content: flex-end; }
.bubble { max-width: min(70%, 680px); padding: 10px 12px; border-radius: 12px; background: var(--bg-bubble-other); box-shadow: var(--shadow); }
.mine .bubble { background: var(--bg-bubble-mine); }.bubble span { display: inline-block; margin-top: 6px; color: var(--text-secondary); font-size: 11px; }
.bubble .reply-reference span { display: block; margin-top: 0; }
.message-content { white-space: pre-wrap; overflow-wrap: anywhere; }
.message-mention { margin: 0; color: var(--accent); font: inherit; font-weight: 600; }
.reaction-bar { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 4px; }
.reaction-button { min-width: 34px; min-height: 30px; padding: 3px 7px; display: inline-flex; align-items: center; justify-content: center; gap: 3px; border: 1px solid var(--border-color); border-radius: 999px; color: var(--text-primary); background: var(--bg-primary); cursor: pointer; }
.reaction-button:hover { background: var(--bg-hover); }.reaction-button.active { border-color: var(--accent); background: var(--bg-active); }.reaction-button:disabled { cursor: wait; opacity: .65; }.reaction-button span { margin: 0; color: inherit; font-size: 16px; }.reaction-button small { font-size: 11px; }
.pin-link { margin-left: 8px; border: 0; background: transparent; color: var(--accent); cursor: pointer; }.pin-link:disabled { opacity: .6; cursor: wait; }
.pin-badge { display: inline-block; margin-bottom: 4px; color: var(--accent); font-size: 12px; font-weight: 600; }
.retry-link { margin-left: 8px; border: 0; color: var(--danger); background: transparent; cursor: pointer; }
.composer { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; padding: 14px 20px; border-top: 1px solid var(--border-color); background: var(--bg-secondary); }
.mention-picker { position: absolute; right: 20px; bottom: 86px; z-index: 10; width: min(360px, calc(100% - 40px)); max-height: 360px; overflow: auto; padding: 12px; border: 1px solid var(--border-color); border-radius: 12px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.mention-picker header { display: flex; align-items: center; justify-content: space-between; }.mention-picker ul { max-height: 230px; overflow: auto; list-style: none; }.mention-picker li button { width: 100%; padding: 10px; display: flex; justify-content: space-between; border: 0; border-radius: 8px; color: var(--text-primary); background: transparent; cursor: pointer; }.mention-picker li button:hover, .mention-picker li button:focus-visible { background: var(--bg-hover); }.mention-picker li span { color: var(--text-secondary); }
.composer textarea { resize: none; }.empty-state { flex: 1; display: grid; place-content: center; text-align: center; color: var(--text-secondary); }
.action-error { position: absolute; right: 20px; bottom: 86px; padding: 8px 12px; border-radius: 8px; background: var(--bg-secondary); box-shadow: var(--shadow); }
.dialog-backdrop { position: fixed; inset: 0; z-index: 30; display: grid; place-items: center; padding: 20px; background: rgb(0 0 0 / 48%); }
.forward-dialog { width: min(480px, 100%); max-height: min(640px, calc(100vh - 40px)); overflow: auto; padding: 20px; border: 1px solid var(--border-color); border-radius: 16px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.forward-dialog header { display: flex; justify-content: space-between; gap: 16px; }.forward-dialog h2 { font-size: 20px; }.forward-dialog header p { margin-top: 4px; color: var(--text-secondary); font-size: 13px; }
.forward-dialog ul { margin-top: 14px; max-height: 360px; overflow: auto; list-style: none; }.forward-dialog li button { width: 100%; padding: 12px; display: flex; justify-content: space-between; border: 0; border-radius: 8px; color: var(--text-primary); background: transparent; cursor: pointer; }.forward-dialog li button:hover, .forward-dialog li button:focus-visible { background: var(--bg-hover); }.forward-dialog li button:disabled { opacity: .6; cursor: wait; }.forward-dialog li span { color: var(--text-secondary); }
.device-dialog { width: min(620px, 100%); max-height: min(720px, calc(100vh - 40px)); display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--border-color); border-radius: 16px; background: var(--bg-secondary); box-shadow: var(--shadow-lg); }
.device-dialog-header { display: flex; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid var(--border-color); }.device-dialog-header h2 { font-size: 20px; }.device-dialog-header p { margin-top: 4px; color: var(--text-secondary); font-size: 13px; }
.icon-button { width: 36px; height: 36px; border: 0; border-radius: 8px; color: var(--text-primary); background: transparent; font-size: 24px; cursor: pointer; }.icon-button:hover { background: var(--bg-hover); }
.device-dialog > .error-msg, .device-notice { margin: 14px 20px 0; padding: 10px 12px; border-radius: 8px; background: var(--bg-primary); }.device-notice { color: var(--text-secondary); }
.device-list { overflow-y: auto; padding: 12px 20px; list-style: none; }.device-row { min-height: 76px; display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border-light); }.device-row:last-child { border-bottom: 0; }
.device-icon { width: 40px; height: 40px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 10px; color: var(--accent); background: var(--bg-active); font-size: 20px; }.device-copy { min-width: 0; flex: 1; display: grid; gap: 3px; }.device-copy span, .device-copy small { color: var(--text-secondary); font-size: 12px; }.current-device { padding: 4px 8px; border-radius: 999px; color: var(--success); background: var(--bg-primary); font-size: 12px; }
.revoke-confirm { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 6px; font-size: 12px; }.btn-danger, .btn-danger-outline { padding: 7px 10px; }.btn-danger { color: white; background: var(--danger); }.btn-danger-outline { border: 1px solid var(--danger); color: var(--danger); background: transparent; }
.device-dialog-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--border-color); }
@media (max-width: 720px) { .preview-header { padding: 10px 14px; }.chat-shell { grid-template-columns: 112px 1fr; }.account-block { padding: 12px; }.account-block span { display: none; }.conversation-button { padding: 12px 10px; }.conversation-button span { display: none; }.bubble { max-width: 88%; }.composer { padding: 10px; } }
</style>
