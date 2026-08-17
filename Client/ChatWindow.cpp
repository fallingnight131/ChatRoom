#include "ChatWindow.h"
#include "NetworkManager.h"
#include "MessageModel.h"
#include "MessageDelegate.h"
#include "EmojiPicker.h"
#include "ThemeManager.h"
#include "TrayManager.h"
#include "FileCache.h"
#include "LocalConversationRepository.h"
#include "AttachmentOutboxService.h"
#include "OutgoingMessageService.h"
#include "ConversationSyncService.h"
#include "V1HistoryPageAdapter.h"
#include "AvatarCropDialog.h"
#include "RoomSettingsDialog.h"
#include "RoomFileManagerDialog.h"
#include "RoomPasswordPromptDialog.h"
#include "RoomSearchDialog.h"
#include "ForwardSelectDialog.h"
#include "FriendSearchDialog.h"
#include "FriendRequestsDialog.h"
#include "ProfileDialog.h"
#include "UserInfoDialog.h"
#include "Protocol.h"
#include "Message.h"
#include "WindowsBandwidthPolicy.h"
#include "WindowsBandwidthPreferenceRepository.h"
#include "WindowsBandwidthViewModel.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"
#include "WindowsConnectionStatusViewModel.h"
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
#include "DeviceManagementDialog.h"
#include "DeviceManagementViewModel.h"
#include "V2WindowsConversationDialog.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "V2WindowsAccountBlockDirectoryDialog.h"
#include "V2WindowsAccountBlockDirectoryViewModel.h"
#include "WindowsDeviceManagementController.h"
#include "WindowsMessageNotificationPresenter.h"
#endif

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QSplitter>
#include <QListView>
#include <QListWidget>
#include <QTextEdit>
#include <QTextCursor>
#include <QPushButton>
#include <QLabel>
#include <QMenuBar>
#include <QMenu>
#include <QAction>
#include <QToolBar>
#include <QStatusBar>
#include <QInputDialog>
#include <QLineEdit>
#include <QFileDialog>
#include <QMessageBox>
#include <QCloseEvent>
#include <QClipboard>
#include <QTimer>
#include <QPixmapCache>
#include <QJsonArray>
#include <QJsonObject>
#include <QApplication>
#include <QScreen>
#include <QSettings>
#include <QScrollBar>
#include <QFile>
#include <QFileInfo>
#include <QUuid>
#include <QDir>
#include <QRegularExpression>
#include <QKeyEvent>
#include <QWheelEvent>
#include <QBuffer>
#include <QPainter>
#include <QPainterPath>
#include <QStackedWidget>
#include <QStyledItemDelegate>
#include <QDesktopServices>
#include <QDebug>

#ifdef Q_OS_WIN
#include <Windows.h>
#include <ShlObj.h>
#include <ShObjIdl.h>
#endif

// 未读红点常量
static const int UnreadRole = Qt::UserRole + 10;

static qint64 syncSequenceFrom(const QJsonObject &data) {
    qint64 sequence = data["sequence"].toVariant().toLongLong();
    sequence = qMax(sequence, data["mutationSequence"].toVariant().toLongLong());
    return qMax(sequence, data["syncSequence"].toVariant().toLongLong());
}

namespace {
struct PendingHistoryDownload {
    int fileId = 0;
    QString fileName;
    qint64 fileSize = 0;
};

bool prepareHistoryMedia(Message *message, PendingHistoryDownload *download) {
    if (!message || message->fileId() == 0) return false;
    if (!message->thumbnail().isEmpty()) {
        const QByteArray thumbnail = QByteArray::fromBase64(
            message->thumbnail().toLatin1());
        if (!thumbnail.isEmpty()) {
            const QString path = FileCache::instance()->thumbDir()
                + QStringLiteral("/thumb_%1.jpg").arg(message->fileId());
            if (!QFile::exists(path)) {
                QFile file(path);
                if (file.open(QIODevice::WriteOnly)) file.write(thumbnail);
            }
        }
    }
    if (message->contentType() != Message::File) return false;
    if (FileCache::instance()->isCached(message->fileId())) {
        message->setDownloadState(Message::Downloaded);
        message->setDownloadProgress(1.0);
        return false;
    }
    static const QSet<QString> imageExtensions = {
        QStringLiteral("png"), QStringLiteral("jpg"),
        QStringLiteral("jpeg"), QStringLiteral("gif"),
        QStringLiteral("bmp"), QStringLiteral("webp")};
    if (!imageExtensions.contains(
            QFileInfo(message->fileName()).suffix().toLower())
        || message->fileCleared() || !download) return false;
    *download = {message->fileId(), message->fileName(), message->fileSize()};
    return true;
}
}

// 红点委托：在列表项右侧绘制未读数量
class UnreadBadgeDelegate : public QStyledItemDelegate {
public:
    using QStyledItemDelegate::QStyledItemDelegate;
    void paint(QPainter *painter, const QStyleOptionViewItem &opt,
               const QModelIndex &idx) const override {
        QStyledItemDelegate::paint(painter, opt, idx);
        int unread = idx.data(UnreadRole).toInt();
        if (unread <= 0) return;
        painter->save();
        painter->setRenderHint(QPainter::Antialiasing);
        QString text = unread > 99 ? "99+" : QString::number(unread);
        QFont f = painter->font();
        f.setPixelSize(10);
        f.setBold(true);
        painter->setFont(f);
        QFontMetrics fm(f);
        int tw = fm.horizontalAdvance(text) + 8;
        int th = 16;
        if (tw < th) tw = th;
        QRectF badge(opt.rect.right() - tw - 6,
                     opt.rect.center().y() - th / 2.0, tw, th);
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor("#e53935"));
        painter->drawRoundedRect(badge, th / 2.0, th / 2.0);
        painter->setPen(Qt::white);
        QRectF textRect = badge.adjusted(0, -1, 0, -1);
        painter->drawText(textRect, Qt::AlignCenter, text);
        painter->restore();
    }
};

// 静态头像缓存
QMap<QString, QPixmap> ChatWindow::s_avatarCache;

QPixmap ChatWindow::avatarForUser(const QString &username) {
    return s_avatarCache.value(username);
}

void ChatWindow::setUpdateCheckAvailable(bool available) {
    if (m_checkForUpdatesAction)
        m_checkForUpdatesAction->setVisible(available);
}

void ChatWindow::requestApplicationQuit() {
    m_forceQuit = true;
    close();
}

#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
bool ChatWindow::configureDeviceManagement(
        const QUrl &endpoint, const QString &deviceId, QByteArray passwordUtf8,
        bool enableMessageForwarding, QList<QUrl> fallbackEndpoints,
        bool enableMessageSearch, bool enableNotifications,
        bool enableAccountBlocking) {
    if (m_deviceManagementController || passwordUtf8.isEmpty()) {
        passwordUtf8.fill('\0');
        return false;
    }
    try {
        m_deviceManagementController =
            std::make_unique<WindowsDeviceManagementController>(
                endpoint, qApp->applicationVersion(), deviceId, m_username,
                std::move(passwordUtf8), nullptr,
                V2WindowsDeviceManagementTransport::SocketHooks{},
                WindowsV2MessagingController::RepositoryFactory{}, nullptr,
                enableMessageForwarding, std::move(fallbackEndpoints),
                enableMessageSearch, enableAccountBlocking);
        m_v2MessageForwardingEnabled = enableMessageForwarding;
        connect(m_deviceManagementController.get(),
                &WindowsDeviceManagementController::messagingReady,
                this, [this] {
                    m_v2MessagingWasReady = true;
                    if (m_v2ConversationAction) {
                        m_v2ConversationAction->setVisible(true);
                        m_v2ConversationAction->setEnabled(true);
                    }
                    if (m_accountBlockDirectoryAction
                            && m_deviceManagementController
                                ->accountBlockDirectoryViewModel()) {
                        m_accountBlockDirectoryAction->setVisible(true);
                        m_accountBlockDirectoryAction->setEnabled(true);
                    }
                });
        connect(m_deviceManagementController.get(),
                &WindowsDeviceManagementController::messagingUnavailable,
                this, [this] {
                    if (m_v2ConversationAction)
                        m_v2ConversationAction->setEnabled(m_v2MessagingWasReady);
                });
        if (enableNotifications) {
            m_v2NotificationPresenter =
                std::make_unique<WindowsMessageNotificationPresenter>(
                    [this](const QString &title, const QString &body,
                           const QString &conversationId) {
                        if (!m_trayManager || !m_trayManager->isAvailable())
                            return false;
                        m_trayManager->showNotification(
                            title, body, conversationId);
                        return true;
                    },
                    [this](const QString &conversationId) {
                        show();
                        raise();
                        activateWindow();
                        showV2Conversations();
                        return m_deviceManagementController
                            && m_deviceManagementController
                                ->conversationDirectoryViewModel()
                                ->openConversation(conversationId);
                    }, 256, m_windowsLocaleViewModel);
            connect(m_deviceManagementController.get(),
                    &WindowsDeviceManagementController::remoteMessagePublished,
                    this,
                    [this](const QString &conversationId,
                           const QString &messageId,
                           const QString &senderAccountId,
                           bool authenticatedAccountMentioned) {
                        if (!m_v2NotificationPresenter) return;
                        WindowsMessageNotificationPolicy::Visibility visibility;
                        if (m_v2ConversationDialog
                                && m_v2ConversationDialog->isActiveWindow()) {
                            visibility.applicationActive = true;
                            visibility.visibleConversationId =
                                m_v2ConversationDialog->selectedConversationId();
                        }
                        m_v2NotificationPresenter->present(
                            {messageId, conversationId, senderAccountId,
                             authenticatedAccountMentioned},
                            visibility);
                    });
            connect(m_trayManager, &TrayManager::notificationActivated,
                    this, [this](const QString &conversationId) {
                        if (m_v2NotificationPresenter)
                            m_v2NotificationPresenter->activate(conversationId);
                    });
        }
        connect(m_deviceManagementController.get(),
                &WindowsDeviceManagementController::messagingFailure,
                this, [this](const QString &) {
                    if (m_v2ConversationAction) {
                        m_v2ConversationAction->setVisible(m_v2MessagingWasReady);
                        m_v2ConversationAction->setEnabled(m_v2MessagingWasReady);
                    }
                });
    } catch (...) {
        m_v2NotificationPresenter.reset();
        passwordUtf8.fill('\0');
        return false;
    }
    if (!m_deviceManagementController->start()) {
        m_v2NotificationPresenter.reset();
        m_deviceManagementController.reset();
        return false;
    }
    if (m_deviceManagementAction) m_deviceManagementAction->setVisible(true);
    return true;
}
#endif

// 创建不随选中状态变色的 QIcon
static QIcon makeStableIcon(const QPixmap &pm) {
    QIcon icon;
    icon.addPixmap(pm, QIcon::Normal);
    icon.addPixmap(pm, QIcon::Selected);
    return icon;
}

// ==================== 构造/析构 ====================

ChatWindow::ChatWindow(QWidget *parent, WindowsLocaleViewModel *localeViewModel)
    : QMainWindow(parent), m_windowsLocaleViewModel(localeViewModel)
{
    m_attachmentOutboxService = std::make_unique<AttachmentOutboxService>();
    m_outgoingMessageService = std::make_unique<OutgoingMessageService>();
    m_conversationSyncService = std::make_unique<ConversationSyncService>();
    m_bandwidthSettings = std::make_unique<QSettings>();
    m_bandwidthRepository =
        std::make_unique<WindowsBandwidthPreferenceRepository>(*m_bandwidthSettings);
    m_bandwidthViewModel =
        std::make_unique<WindowsBandwidthViewModel>(m_bandwidthRepository.get());
    m_connectionStatusViewModel =
        std::make_unique<WindowsConnectionStatusViewModel>();
    m_avatarRequests = std::make_unique<WindowsAvatarRequestCoordinator>(
        [](const QString &username) {
            QJsonObject data;
            data["username"] = username;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::AVATAR_GET_REQ, data));
        });
    m_avatarRequests->setLowBandwidthEnabled(m_bandwidthViewModel->enabled());
    if (!m_windowsLocaleViewModel) {
        m_windowsLocaleSettings = std::make_unique<QSettings>();
        m_windowsLocaleRepository =
            std::make_unique<WindowsLocalePreferenceRepository>(
                *m_windowsLocaleSettings);
        m_ownedWindowsLocaleViewModel =
            std::make_unique<WindowsLocaleViewModel>(
                m_windowsLocaleRepository.get());
        m_windowsLocaleViewModel = m_ownedWindowsLocaleViewModel.get();
    }
    setWindowTitle(WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale()).mainWindowTitle);
    setWindowFlags(Qt::Window | Qt::WindowMinimizeButtonHint | Qt::WindowMaximizeButtonHint | Qt::WindowCloseButtonHint);
    resize(1000, 700);

    setupUi();
    setupMenuBar();
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            this, &ChatWindow::refreshWindowChrome);
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            this, &ChatWindow::refreshConnectionStatus);
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            this, &ChatWindow::refreshComposerText);
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            this, &ChatWindow::refreshNavigationText);
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            this, &ChatWindow::refreshConversationShellText);
    connect(m_connectionStatusViewModel.get(),
            &WindowsConnectionStatusViewModel::changed,
            this, &ChatWindow::refreshConnectionStatus);
    connectSignals();
    connect(m_bandwidthViewModel.get(), &WindowsBandwidthViewModel::changed,
            this, [this] {
                m_avatarRequests->setLowBandwidthEnabled(
                    m_bandwidthViewModel->enabled());
            });

    // 系统托盘
    m_trayManager = new TrayManager(this, m_windowsLocaleViewModel);

    // 应用主题
    ThemeManager::instance()->applyTheme(qApp);
}

ChatWindow::~ChatWindow() = default;

void ChatWindow::setCurrentUser(int userId, const QString &username, const QString &displayName) {
    m_userId   = userId;
    m_username = username;
    m_displayName = displayName;
    refreshWindowChrome();
    m_nicknameLabel->setText(displayName);

    // 设置用户缓存目录（以用户uniqueId隔离）
    FileCache::instance()->setUsername(username);

    auto repository = std::make_unique<LocalConversationRepository>(
        LocalConversationRepository::defaultDatabasePath(username));
    if (repository->initialize()) {
        m_localRepository = std::move(repository);
    } else {
        m_localRepository.reset();
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=activate outcome=degraded detail=%1")
            .arg(repository->lastError());
        m_statusLabel->setText(QStringLiteral("本地消息缓存不可用，已切换为在线模式"));
    }
    m_outgoingMessageService->setRepository(m_localRepository.get());
    m_attachmentOutboxService->setRepository(m_localRepository.get());
    m_conversationSyncService->setContext(
        m_localRepository.get(), m_username, true);

    requestRoomList();

    // 请求好友列表（获取未读计数和好友申请提醒）
    onRefreshFriendList();

    // 请求自己的头像
    requestAvatar(username);
}

// ==================== 事件过滤器 (Enter发送) ====================

bool ChatWindow::eventFilter(QObject *watched, QEvent *event) {
    if (watched == m_inputEdit && event->type() == QEvent::KeyPress) {
        auto *keyEvent = static_cast<QKeyEvent*>(event);
        if (keyEvent->key() == Qt::Key_Return || keyEvent->key() == Qt::Key_Enter) {
            if (keyEvent->modifiers() & Qt::ShiftModifier) {
                // Shift+Enter -> 换行
                return false; // 让 QTextEdit 处理
            }
            // 纯 Enter -> 发送消息
            onSendMessage();
            return true; // 拦截，不插入换行
        }
    }
    if (watched == m_avatarPreview && event->type() == QEvent::MouseButtonDblClick) {
        showProfileDialog();
        return true;
    }
    // 消息列表滚轮减速：每次滚动像素量减小
    if (m_messageView && watched == m_messageView->viewport() && event->type() == QEvent::Wheel) {
        auto *wheelEvent = static_cast<QWheelEvent*>(event);
        int delta = wheelEvent->angleDelta().y();
        // 原始距离除以 3 ，实现慢速滚动
        int pixels = -delta / 3;
        m_messageView->verticalScrollBar()->setValue(
            m_messageView->verticalScrollBar()->value() + pixels);
        return true; // 拦截默认的快速滚动
    }
    return QMainWindow::eventFilter(watched, event);
}

// ==================== UI 构建 ====================

void ChatWindow::setupUi() {
    auto *centralWidget = new QWidget;
    setCentralWidget(centralWidget);

    auto *mainLayout = new QHBoxLayout(centralWidget);
    mainLayout->setContentsMargins(0, 0, 0, 0);
    mainLayout->setSpacing(0);

    m_splitter = new QSplitter(Qt::Horizontal);

    // --- 左侧：房间列表 ---
    auto *leftPanel = new QWidget;
    auto *leftLayout = new QVBoxLayout(leftPanel);
    leftLayout->setContentsMargins(4, 4, 4, 4);

    // 头像区域
    auto *avatarLayout = new QHBoxLayout;
    m_avatarPreview = new QLabel;
    m_avatarPreview->setFixedSize(40, 40);
    m_avatarPreview->setStyleSheet("border: 1px solid #ccc; border-radius: 20px; background: #ddd;");
    m_avatarPreview->setScaledContents(true);
    m_avatarPreview->setAlignment(Qt::AlignCenter);
    m_avatarPreview->setCursor(Qt::PointingHandCursor);
    m_avatarPreview->installEventFilter(this);

    m_nicknameLabel = new QLabel;
    m_nicknameLabel->setStyleSheet("font-weight: bold; font-size: 13px; padding-left: 6px;");
    m_nicknameLabel->setWordWrap(true);
    avatarLayout->addWidget(m_avatarPreview);
    avatarLayout->addWidget(m_nicknameLabel, 1);
    leftLayout->addLayout(avatarLayout);

    // --- 房间/好友 切换标签 ---
    auto *tabLayout = new QHBoxLayout;
    m_tabRoomBtn   = new QPushButton;
    m_tabFriendBtn = new QPushButton;
    m_tabRoomBtn->setCheckable(true);
    m_tabFriendBtn->setCheckable(true);
    m_tabRoomBtn->setChecked(false);
    m_tabFriendBtn->setChecked(true);
    m_tabRoomBtn->setStyleSheet("QPushButton { font-weight: bold; font-size: 13px; padding: 6px; border: none; border-bottom: 2px solid #4CAF50; }"
                                 "QPushButton:!checked { border-bottom: 2px solid transparent; color: #888; }");
    m_tabFriendBtn->setStyleSheet("QPushButton { font-weight: bold; font-size: 13px; padding: 6px; border: none; border-bottom: 2px solid #4CAF50; }"
                                   "QPushButton:!checked { border-bottom: 2px solid transparent; color: #888; }");
    // 红点指示器（作为子控件覆盖在按钮上）
    auto createDot = [](QWidget *parent) -> QLabel* {
        auto *dot = new QLabel(parent);
        dot->setFixedSize(8, 8);
        dot->setStyleSheet("background: #e53935; border-radius: 4px;");
        dot->hide();
        return dot;
    };
    m_tabRoomDot = createDot(m_tabRoomBtn);
    m_tabFriendDot = createDot(m_tabFriendBtn);
    tabLayout->addWidget(m_tabFriendBtn);
    tabLayout->addWidget(m_tabRoomBtn);
    leftLayout->addLayout(tabLayout);

    // --- 房间列表面板 ---
    auto *roomPanel = new QWidget;
    auto *roomPanelLayout = new QVBoxLayout(roomPanel);
    roomPanelLayout->setContentsMargins(0, 0, 0, 0);

    m_roomList = new QListWidget;
    m_roomList->setMinimumWidth(160);
    m_roomList->setIconSize(QSize(36, 36));
    m_roomList->setContextMenuPolicy(Qt::CustomContextMenu);
    m_roomList->setItemDelegate(new UnreadBadgeDelegate(m_roomList));
    roomPanelLayout->addWidget(m_roomList);

    m_roomBtnPanel = new QWidget;
    auto *roomBtnLayout = new QHBoxLayout(m_roomBtnPanel);
    roomBtnLayout->setContentsMargins(0, 4, 0, 0);
    m_createRoomBtn = new QPushButton;
    m_searchRoomBtn = new QPushButton;
    m_refreshRoomBtn = new QPushButton;
    roomBtnLayout->addWidget(m_searchRoomBtn);
    roomBtnLayout->addWidget(m_createRoomBtn);
    roomBtnLayout->addWidget(m_refreshRoomBtn);
    roomPanelLayout->addWidget(m_roomBtnPanel);

    // --- 好友列表面板 ---
    auto *friendPanel = new QWidget;
    auto *friendPanelLayout = new QVBoxLayout(friendPanel);
    friendPanelLayout->setContentsMargins(0, 0, 0, 0);

    m_friendList = new QListWidget;
    m_friendList->setMinimumWidth(160);
    m_friendList->setIconSize(QSize(36, 36));
    m_friendList->setContextMenuPolicy(Qt::CustomContextMenu);
    m_friendList->setItemDelegate(new UnreadBadgeDelegate(m_friendList));
    friendPanelLayout->addWidget(m_friendList);

    m_friendBtnPanel = new QWidget;
    auto *friendBtnLayout = new QHBoxLayout(m_friendBtnPanel);
    friendBtnLayout->setContentsMargins(0, 4, 0, 0);
    m_addFriendBtn = new QPushButton;
    m_friendReqBtn = new QPushButton;
    m_friendReqDot = createDot(m_friendReqBtn);
    m_refreshFriendBtn = new QPushButton;
    friendBtnLayout->addWidget(m_addFriendBtn);
    friendBtnLayout->addWidget(m_friendReqBtn);
    friendBtnLayout->addWidget(m_refreshFriendBtn);
    friendPanelLayout->addWidget(m_friendBtnPanel);

    // --- 堆叠切换 ---
    m_listStack = new QStackedWidget;
    m_listStack->addWidget(roomPanel);    // index 0 = 房间
    m_listStack->addWidget(friendPanel);  // index 1 = 好友
    m_listStack->setCurrentIndex(1);
    leftLayout->addWidget(m_listStack, 1);

    connect(m_tabRoomBtn, &QPushButton::clicked, this, [this] {
        m_tabRoomBtn->setChecked(true);
        m_tabFriendBtn->setChecked(false);
        m_listStack->setCurrentIndex(0);
    });
    connect(m_tabFriendBtn, &QPushButton::clicked, this, [this] {
        m_tabRoomBtn->setChecked(false);
        m_tabFriendBtn->setChecked(true);
        m_listStack->setCurrentIndex(1);
        onRefreshFriendList();
    });

    connect(m_createRoomBtn, &QPushButton::clicked, this, &ChatWindow::onCreateRoom);
    connect(m_searchRoomBtn, &QPushButton::clicked, this, &ChatWindow::onSearchRoom);
    connect(m_refreshRoomBtn, &QPushButton::clicked, this, &ChatWindow::requestRoomList);
    connect(m_addFriendBtn, &QPushButton::clicked, this, &ChatWindow::onAddFriend);
    connect(m_friendReqBtn,    &QPushButton::clicked, this, &ChatWindow::onShowFriendRequests);
    connect(m_refreshFriendBtn, &QPushButton::clicked, this, &ChatWindow::onRefreshFriendList);
    connect(m_friendList, &QListWidget::itemClicked, this, &ChatWindow::onFriendSelected);
    connect(m_friendList, &QListWidget::customContextMenuRequested, this, &ChatWindow::onFriendContextMenu);

    // --- 中间：消息区域 ---
    auto *centerPanel = new QWidget;
    auto *centerLayout = new QVBoxLayout(centerPanel);
    centerLayout->setContentsMargins(4, 4, 4, 4);

    m_roomTitle = new QLabel;
    m_roomTitle->setStyleSheet("font-weight: bold; font-size: 16px; padding: 8px;");

    // 用图标代替文字，避免字体渲染问题
    {
        QPixmap dotsPix(32, 32);
        dotsPix.fill(Qt::transparent);
        QPainter dp(&dotsPix);
        dp.setRenderHint(QPainter::Antialiasing);
        dp.setBrush(QColor("#555"));
        dp.setPen(Qt::NoPen);
        int r = 3, cy = 16;
        dp.drawEllipse(QPoint(8,  cy), r, r);
        dp.drawEllipse(QPoint(16, cy), r, r);
        dp.drawEllipse(QPoint(24, cy), r, r);
        dp.end();
        m_roomSettingsBtn = new QPushButton;
        m_roomSettingsBtn->setIcon(QIcon(dotsPix));
        m_roomSettingsBtn->setIconSize(QSize(32, 32));
    }
    m_roomSettingsBtn->setFixedSize(32, 32);
    m_roomSettingsBtn->setStyleSheet("QPushButton { border: none; background: transparent; }"
                                      "QPushButton:hover { background-color: rgba(200,200,200,0.5); border-radius: 4px; }");
    m_roomSettingsBtn->setVisible(false); // 未选择房间时隐藏

    auto *titleLayout = new QHBoxLayout;
    titleLayout->setContentsMargins(0, 0, 0, 0);
    titleLayout->addWidget(m_roomTitle, 1);
    titleLayout->addWidget(m_roomSettingsBtn);
    centerLayout->addLayout(titleLayout);

    m_messageView = new QListView;
    m_messageView->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_messageView->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_messageView->setSelectionMode(QAbstractItemView::SingleSelection);
    m_messageView->setContextMenuPolicy(Qt::CustomContextMenu);
    m_messageView->setWordWrap(true);
    m_messageView->setSpacing(2);

    // 安装事件过滤器以拦截滚轮事件
    m_messageView->viewport()->installEventFilter(this);

    m_delegate = new MessageDelegate(this);
    m_messageView->setItemDelegate(m_delegate);
    centerLayout->addWidget(m_messageView, 1);

    // 输入区域
    auto *inputPanel = new QWidget;
    auto *inputLayout = new QVBoxLayout(inputPanel);
    inputLayout->setContentsMargins(0, 4, 0, 0);

    // 工具栏
    auto *toolLayout = new QHBoxLayout;
    m_emojiBtn = new QPushButton;
    m_emojiBtn->setFixedHeight(32);

    m_fileBtn = new QPushButton;
    m_fileBtn->setFixedHeight(32);

    toolLayout->addWidget(m_emojiBtn);
    toolLayout->addWidget(m_fileBtn);
    toolLayout->addStretch();
    inputLayout->addLayout(toolLayout);

    auto *sendLayout = new QHBoxLayout;
    m_inputEdit = new QTextEdit;
    m_inputEdit->setMaximumHeight(80);
    m_inputEdit->installEventFilter(this);
    m_inputEdit->setContextMenuPolicy(Qt::CustomContextMenu);
    m_draftSaveTimer = new QTimer(this);
    m_draftSaveTimer->setSingleShot(true);
    m_draftSaveTimer->setInterval(400);
    connect(m_draftSaveTimer, &QTimer::timeout, this, &ChatWindow::flushCurrentDraft);
    connect(m_inputEdit, &QTextEdit::textChanged, this, [this] {
        if (!m_restoringDraft) m_draftSaveTimer->start();
    });
    connect(m_inputEdit, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
        QMenu *menu = m_inputEdit->createStandardContextMenu();
        menu->addSeparator();
        menu->addAction(WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale()).mainComposerInsertLineBreak,
                        [this] {
            m_inputEdit->insertPlainText("\n");
        });
        menu->exec(m_inputEdit->mapToGlobal(pos));
        delete menu;
    });
    m_sendBtn = new QPushButton;
    m_sendBtn->setFixedSize(80, 60);
    m_sendBtn->setStyleSheet("QPushButton { background-color: #4CAF50; color: white; "
                              "border-radius: 4px; font-size: 14px; }"
                              "QPushButton:hover { background-color: #45a049; }");

    sendLayout->addWidget(m_inputEdit, 1);
    sendLayout->addWidget(m_sendBtn);
    inputLayout->addLayout(sendLayout);

    centerLayout->addWidget(inputPanel);
    refreshComposerText();

    // --- 右侧：用户列表 ---
    m_rightPanel = new QWidget;
    auto *rightLayout = new QVBoxLayout(m_rightPanel);
    rightLayout->setContentsMargins(4, 4, 4, 4);

    m_memberListLabel = new QLabel;
    m_memberListLabel->setStyleSheet("font-weight: bold; font-size: 14px; padding: 4px;");
    rightLayout->addWidget(m_memberListLabel);

    m_userList = new QListWidget;
    m_userList->setMinimumWidth(140);
    m_userList->setContextMenuPolicy(Qt::CustomContextMenu);
    rightLayout->addWidget(m_userList);

    // 组装
    m_splitter->addWidget(leftPanel);
    m_splitter->addWidget(centerPanel);
    m_splitter->addWidget(m_rightPanel);
    m_splitter->setStretchFactor(0, 1);
    m_splitter->setStretchFactor(1, 4);
    m_splitter->setStretchFactor(2, 1);

    mainLayout->addWidget(m_splitter);

    // 状态栏
    m_statusLabel = new QLabel;
    m_connectionStatusLabel = new QLabel;
    statusBar()->addWidget(m_statusLabel, 1);
    statusBar()->addPermanentWidget(m_connectionStatusLabel);
    refreshConnectionStatus();

    // 表情选择器（弹窗）
    m_emojiPicker = new EmojiPicker(this, m_windowsLocaleViewModel);
    m_emojiPicker->hide();
    refreshNavigationText();
    refreshConversationShellText();
}

void ChatWindow::setupMenuBar() {
    m_fileMenu = menuBar()->addMenu(QString{});
    m_logoutAction = m_fileMenu->addAction(
        QString{}, this, &ChatWindow::onLogout);
    m_fileMenu->addSeparator();
    m_quitAction = m_fileMenu->addAction(QString{}, QKeySequence::Quit, this, [this] {
        m_forceQuit = true;
        close();
    });

    m_viewMenu = menuBar()->addMenu(QString{});
    m_toggleThemeAction = m_viewMenu->addAction(
        QString{}, QKeySequence("Ctrl+T"), this, &ChatWindow::onToggleTheme);

    m_settingsMenu = menuBar()->addMenu(QString{});
    m_profileAction = m_settingsMenu->addAction(
        QString{}, this, &ChatWindow::showProfileDialog);
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    m_deviceManagementAction = m_settingsMenu->addAction(
        QString{}, this,
        &ChatWindow::showDeviceManagement);
    m_deviceManagementAction->setVisible(false);
    m_v2ConversationAction = m_settingsMenu->addAction(
        QString{}, this,
        &ChatWindow::showV2Conversations);
    m_v2ConversationAction->setVisible(false);
    m_accountBlockDirectoryAction = m_settingsMenu->addAction(
        QString{}, this,
        &ChatWindow::showAccountBlockDirectory);
    m_accountBlockDirectoryAction->setVisible(false);
#endif
    m_settingsMenu->addSeparator();
    m_cachePathAction = m_settingsMenu->addAction(
        QString{}, this, &ChatWindow::onChangeCacheDir);
    m_clearCacheAction = m_settingsMenu->addAction(
        QString{}, this, &ChatWindow::onClearCache);
    m_pendingAttachmentsAction = m_settingsMenu->addAction(
        QString{}, this, &ChatWindow::showPendingAttachments);

    m_helpMenu = menuBar()->addMenu(QString{});
    m_checkForUpdatesAction = m_helpMenu->addAction(
        QString{}, this,
        &ChatWindow::checkForUpdatesRequested);
    m_checkForUpdatesAction->setVisible(false);
    m_helpMenu->addSeparator();
    m_aboutAction = m_helpMenu->addAction(
        QString{}, this, &ChatWindow::showAboutDialog);
    refreshWindowChrome();
}

void ChatWindow::refreshWindowChrome() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    setWindowTitle(m_displayName.isEmpty()
        ? copy.mainWindowTitle : copy.mainWindowTitleForUser.arg(m_displayName));
    m_fileMenu->setTitle(copy.mainMenuFile);
    m_viewMenu->setTitle(copy.mainMenuView);
    m_settingsMenu->setTitle(copy.mainMenuSettings);
    m_helpMenu->setTitle(copy.mainMenuHelp);
    m_logoutAction->setText(copy.mainMenuLogout);
    m_quitAction->setText(copy.mainMenuQuit);
    m_toggleThemeAction->setText(copy.mainMenuToggleTheme);
    m_profileAction->setText(copy.mainMenuProfile);
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    m_deviceManagementAction->setText(copy.mainMenuDevices);
    m_v2ConversationAction->setText(copy.mainMenuV2Preview);
    m_accountBlockDirectoryAction->setText(copy.mainMenuBlockedAccounts);
#endif
    m_cachePathAction->setText(copy.mainMenuCachePath);
    m_clearCacheAction->setText(copy.mainMenuClearCache);
    m_pendingAttachmentsAction->setText(copy.mainMenuPendingAttachments);
    m_checkForUpdatesAction->setText(copy.mainMenuCheckUpdates);
    m_aboutAction->setText(copy.mainMenuAbout);
}

void ChatWindow::refreshConnectionStatus() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    m_statusLabel->setAccessibleName(copy.mainActivityStatusAccessible);
    m_connectionStatusLabel->setAccessibleName(
        copy.mainConnectionStatusAccessible);
    switch (m_connectionStatusViewModel->state()) {
    case WindowsConnectionStatusViewModel::State::Disconnected:
        m_connectionStatusLabel->setText(copy.mainDisconnected);
        m_connectionStatusLabel->setStyleSheet(QStringLiteral("color: red;"));
        break;
    case WindowsConnectionStatusViewModel::State::Connected:
        m_connectionStatusLabel->setText(copy.mainConnected);
        m_connectionStatusLabel->setStyleSheet(QStringLiteral("color: green;"));
        break;
    case WindowsConnectionStatusViewModel::State::Reconnecting:
        m_connectionStatusLabel->setText(copy.mainReconnecting.arg(
            m_connectionStatusViewModel->reconnectAttempt()));
        m_connectionStatusLabel->setStyleSheet(QStringLiteral("color: orange;"));
        break;
    }
}

void ChatWindow::refreshComposerText() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    m_emojiBtn->setText(copy.mainComposerEmoji);
    m_emojiBtn->setToolTip(copy.mainComposerEmojiTooltip);
    m_emojiBtn->setAccessibleName(copy.mainComposerEmojiAccessible);
    m_fileBtn->setText(copy.mainComposerFile);
    m_fileBtn->setToolTip(copy.mainComposerFileTooltip);
    m_fileBtn->setAccessibleName(copy.mainComposerFileAccessible);
    m_inputEdit->setPlaceholderText(copy.mainComposerPlaceholder);
    m_inputEdit->setAccessibleName(copy.mainComposerInputAccessible);
    m_sendBtn->setText(copy.mainComposerSend);
    m_sendBtn->setAccessibleName(copy.mainComposerSendAccessible);
}

void ChatWindow::refreshNavigationText() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (m_avatarPreview->pixmap().isNull())
        m_avatarPreview->setText(copy.mainNavigationAvatarFallback);
    m_avatarPreview->setAccessibleName(copy.mainNavigationProfileAccessible);
    m_tabRoomBtn->setText(copy.mainNavigationRooms);
    m_tabRoomBtn->setAccessibleName(copy.mainNavigationRoomsAccessible);
    m_tabFriendBtn->setText(copy.mainNavigationFriends);
    m_tabFriendBtn->setAccessibleName(copy.mainNavigationFriendsAccessible);
    m_roomList->setAccessibleName(copy.mainNavigationRoomListAccessible);
    m_friendList->setAccessibleName(copy.mainNavigationFriendListAccessible);
    m_createRoomBtn->setText(copy.mainNavigationCreateRoom);
    m_createRoomBtn->setAccessibleName(copy.mainNavigationCreateRoomAccessible);
    m_searchRoomBtn->setText(copy.search);
    m_searchRoomBtn->setAccessibleName(copy.mainNavigationSearchRoomsAccessible);
    m_refreshRoomBtn->setText(copy.refresh);
    m_refreshRoomBtn->setAccessibleName(copy.mainNavigationRefreshRoomsAccessible);
    m_addFriendBtn->setText(copy.mainNavigationSearchFriends);
    m_addFriendBtn->setAccessibleName(copy.mainNavigationSearchFriendsAccessible);
    m_refreshFriendBtn->setText(copy.refresh);
    m_refreshFriendBtn->setAccessibleName(
        copy.mainNavigationRefreshFriendsAccessible);
    m_friendReqBtn->setAccessibleName(copy.mainNavigationFriendRequestsAccessible);
    refreshFriendListPresentation();
    updateUnreadDots();
}

void ChatWindow::refreshFriendListPresentation() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    for (int index = 0; index < m_friendList->count(); ++index) {
        auto *item = m_friendList->item(index);
        const QString username = item->data(Qt::UserRole).toString();
        const QString displayName = item->data(Qt::UserRole + 1).toString();
        const bool online = item->data(Qt::UserRole + 3).toBool();
        const QString identity = displayName.isEmpty() ? username : displayName;
        item->setText(online
            ? copy.mainNavigationFriendOnline.arg(identity) : identity);
        item->setForeground(online ? QColor("#4CAF50") : QColor("#999"));
        item->setData(Qt::AccessibleTextRole, online
            ? copy.mainNavigationFriendOnlineAccessible.arg(identity)
            : copy.mainNavigationFriendOfflineAccessible.arg(identity));
    }
}

void ChatWindow::refreshConversationShellText() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    QString title = copy.mainConversationEmptyTitle;
    if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
        const QString identity = m_currentFriendDisplayName.isEmpty()
            ? m_currentFriendUsername : m_currentFriendDisplayName;
        title = copy.mainConversationDirectTitle.arg(identity);
    } else if (m_currentRoomId > 0) {
        for (int index = 0; index < m_roomList->count(); ++index) {
            auto *item = m_roomList->item(index);
            if (item->data(Qt::UserRole).toInt() != m_currentRoomId) continue;
            title = m_adminRooms.value(m_currentRoomId, false)
                ? copy.mainConversationAdminTitle.arg(item->text())
                : item->text();
            break;
        }
    }
    m_roomTitle->setText(title);
    m_roomTitle->setAccessibleName(copy.mainConversationTitleAccessible.arg(title));
    m_roomSettingsBtn->setToolTip(copy.mainConversationRoomSettings);
    m_roomSettingsBtn->setAccessibleName(
        copy.mainConversationRoomSettingsAccessible);
    m_memberListLabel->setText(copy.mainConversationMembers);
    m_userList->setAccessibleName(copy.mainConversationMemberListAccessible);
    refreshMemberListPresentation();
}

void ChatWindow::refreshMemberListPresentation() {
    for (int index = 0; index < m_userList->count(); ++index)
        updateUserListItemWidget(m_userList->item(index));
}

void ChatWindow::showAboutDialog() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    QMessageBox::about(this, copy.mainAboutTitle, copy.mainAboutBody);
}

// ==================== 信号连接 ====================

void ChatWindow::connectSignals() {
    auto *net = NetworkManager::instance();

    // 连接状态
    connect(net, &NetworkManager::connected,     this, &ChatWindow::onConnected);
    connect(net, &NetworkManager::disconnected,   this, &ChatWindow::onDisconnected);
    connect(net, &NetworkManager::reconnecting,   this, &ChatWindow::onReconnecting);

    // 房间
    connect(net, &NetworkManager::roomCreated,     this, &ChatWindow::onRoomCreated);
    connect(net, &NetworkManager::roomJoined,      this, &ChatWindow::onRoomJoined);
    connect(net, &NetworkManager::roomListReceived,this, &ChatWindow::onRoomListReceived);

    // 用户列表
    connect(net, &NetworkManager::userListReceived,this, &ChatWindow::onUserListReceived);
    connect(net, &NetworkManager::userJoined,      this, &ChatWindow::onUserJoined);
    connect(net, &NetworkManager::userLeft,        this, &ChatWindow::onUserLeft);
    connect(net, &NetworkManager::userOnline,      this, &ChatWindow::onUserOnline);
    connect(net, &NetworkManager::userOffline,     this, &ChatWindow::onUserOffline);
    connect(net, &NetworkManager::leaveRoomResponse, this, &ChatWindow::onLeaveRoomResponse);

    // 消息
    connect(net, &NetworkManager::chatMessageReceived,   this, &ChatWindow::onChatMessage);
    connect(net, &NetworkManager::chatSendResponse,
            this, &ChatWindow::handleRoomSendResponse);
    connect(net, &NetworkManager::systemMessageReceived, this, &ChatWindow::onSystemMessage);
    connect(net, &NetworkManager::historyReceived,       this, &ChatWindow::onHistoryReceived);

    // 文件
    connect(net, &NetworkManager::fileNotify,       this, &ChatWindow::onFileNotify);
    connect(net, &NetworkManager::fileDownloadReady, this, &ChatWindow::onFileDownloadReady);

    // 大文件分块传输
    connect(net, &NetworkManager::uploadStartResponse, this, &ChatWindow::onUploadStartResponse);
    connect(net, &NetworkManager::uploadChunkResponse, this, &ChatWindow::onUploadChunkResponse);
    connect(net, &NetworkManager::uploadFinalizeResponse, this, &ChatWindow::onUploadFinalizeResponse);
    connect(net, &NetworkManager::rawUploadProgress, this, &ChatWindow::onRawUploadProgress);
    connect(net, &NetworkManager::rawUploadFinished, this, &ChatWindow::onRawUploadFinished);
    connect(net, &NetworkManager::rawDownloadProgress, this, &ChatWindow::onRawDownloadProgress);
    connect(net, &NetworkManager::rawDownloadFinished, this, &ChatWindow::onRawDownloadFinished);
    connect(net, &NetworkManager::downloadChunkResponse, this, &ChatWindow::onDownloadChunkResponse);
    connect(net, &NetworkManager::fileCosProgress, this, &ChatWindow::onFileCosProgress);
    connect(net, &NetworkManager::fileForwardResponse, this, &ChatWindow::onFileForwardResponse);

    // 撤回
    connect(net, &NetworkManager::recallResponse, this, &ChatWindow::onRecallResponse);
    connect(net, &NetworkManager::recallNotify,   this, &ChatWindow::onRecallNotify);

    // 管理员
    connect(net, &NetworkManager::adminStatusChanged, this, &ChatWindow::onAdminStatusChanged);
    connect(net, &NetworkManager::setAdminResponse,   this, &ChatWindow::onSetAdminResponse);
    connect(net, &NetworkManager::deleteMsgsResponse, this, &ChatWindow::onDeleteMsgsResponse);
    connect(net, &NetworkManager::deleteMsgsNotify,   this, &ChatWindow::onDeleteMsgsNotify);

    // UI 交互
    connect(m_sendBtn,     &QPushButton::clicked, this, &ChatWindow::onSendMessage);
    connect(m_emojiBtn,    &QPushButton::clicked, this, &ChatWindow::onShowEmojiPicker);
    connect(m_fileBtn,     &QPushButton::clicked, this, &ChatWindow::onSendFile);
    connect(m_roomSettingsBtn, &QPushButton::clicked, this, [this] {
        if (m_currentRoomId > 0) showRoomSettingsDialog(m_currentRoomId);
    });
    connect(m_roomList,    &QListWidget::itemClicked, this, &ChatWindow::onRoomSelected);
    connect(m_roomList,    &QListWidget::customContextMenuRequested, this, &ChatWindow::onRoomContextMenu);
    connect(m_emojiPicker, &EmojiPicker::emojiSelected, this, &ChatWindow::onEmojiSelected);
    connect(m_messageView, &QListView::customContextMenuRequested, this, &ChatWindow::onMessageContextMenu);
    connect(m_userList,    &QListWidget::customContextMenuRequested, this, &ChatWindow::onUserContextMenu);

    // 头像
    connect(net, &NetworkManager::avatarUploadResponse, this, &ChatWindow::onAvatarUploadResponse);
    connect(net, &NetworkManager::avatarGetResponse,    this, &ChatWindow::onAvatarGetResponse);
    connect(net, &NetworkManager::avatarUpdateNotify,   this, &ChatWindow::onAvatarUpdateNotify);

    // 聊天室头像
    connect(net, &NetworkManager::roomAvatarUploadResponse, this, [this](int roomId, bool success, const QString &error) {
        if (success) {
            // 上传成功后重新请求头像以刷新本地缓存
            QJsonObject reqData;
            reqData["roomId"] = roomId;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_GET_REQ, reqData));
            QMessageBox::information(this, QStringLiteral("修改成功"), QStringLiteral("聊天室头像修改成功"));
        } else {
            QMessageBox::warning(this, QStringLiteral("修改失败"),
                                 error.isEmpty() ? QStringLiteral("上传聊天室头像失败") : error);
        }
    });
    connect(net, &NetworkManager::roomAvatarGetResponse, this, [this](int roomId, bool success, const QByteArray &avatarData) {
        if (success && !avatarData.isEmpty()) {
            QPixmap pix;
            pix.loadFromData(avatarData);
            if (!pix.isNull()) {
                m_roomAvatarCache[roomId] = pix.scaled(36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation);
                updateRoomListAvatars();
            }
        }
    });
    connect(net, &NetworkManager::roomAvatarUpdateNotify, this, [this](int roomId, const QByteArray &avatarData) {
        if (!avatarData.isEmpty()) {
            QPixmap pix;
            pix.loadFromData(avatarData);
            if (!pix.isNull()) {
                m_roomAvatarCache[roomId] = pix.scaled(36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation);
                updateRoomListAvatars();
            }
        }
    });

    // 房间设置
    connect(net, &NetworkManager::roomSettingsResponse, this, &ChatWindow::onRoomSettingsResponse);
    connect(net, &NetworkManager::roomSettingsNotify,   this, &ChatWindow::onRoomSettingsNotify);
    connect(net, &NetworkManager::roomFilesResponse, this, &ChatWindow::onRoomFilesResponse);
    connect(net, &NetworkManager::roomFilesDeleteResponse, this, &ChatWindow::onRoomFilesDeleteResponse);
    connect(net, &NetworkManager::roomFilesNotify, this, &ChatWindow::onRoomFilesNotify);

    // 删除聊天室
    connect(net, &NetworkManager::deleteRoomResponse, this, &ChatWindow::onDeleteRoomResponse);
    connect(net, &NetworkManager::deleteRoomNotify,   this, &ChatWindow::onDeleteRoomNotify);

    // 重命名聊天室
    connect(net, &NetworkManager::renameRoomResponse, this, &ChatWindow::onRenameRoomResponse);
    connect(net, &NetworkManager::renameRoomNotify,   this, &ChatWindow::onRenameRoomNotify);

    // 聊天室密码
    connect(net, &NetworkManager::setRoomPasswordResponse, this, &ChatWindow::onSetRoomPasswordResponse);
    connect(net, &NetworkManager::getRoomPasswordResponse, this, &ChatWindow::onGetRoomPasswordResponse);
    connect(net, &NetworkManager::joinRoomNeedPassword,    this, &ChatWindow::onJoinRoomNeedPassword);

    // 踢人
    connect(net, &NetworkManager::kickUserResponse,  this, &ChatWindow::onKickUserResponse);
    connect(net, &NetworkManager::kickedFromRoom,    this, &ChatWindow::onKickedFromRoom);

    // 昵称修改
    connect(net, &NetworkManager::changeNicknameResponse, this, &ChatWindow::onChangeNicknameResponse);
    connect(net, &NetworkManager::nicknameChangeNotify,   this, &ChatWindow::onNicknameChangeNotify);

    // 用户ID修改
    connect(net, &NetworkManager::changeUidResponse, this, &ChatWindow::onChangeUidResponse);
    connect(net, &NetworkManager::uidChangeNotify,   this, &ChatWindow::onUidChangeNotify);

    // 好友系统
    connect(net, &NetworkManager::friendRequestResponse,  this, &ChatWindow::onFriendRequestResponse);
    connect(net, &NetworkManager::friendRequestNotify,    this, &ChatWindow::onFriendRequestNotify);
    connect(net, &NetworkManager::friendAcceptResponse,   this, &ChatWindow::onFriendAcceptResponse);
    connect(net, &NetworkManager::friendAcceptNotify,     this, &ChatWindow::onFriendAcceptNotify);
    connect(net, &NetworkManager::friendRejectResponse,   this, &ChatWindow::onFriendRejectResponse);
    connect(net, &NetworkManager::friendRemoveResponse,   this, &ChatWindow::onFriendRemoveResponse);
    connect(net, &NetworkManager::friendRemoveNotify,      this, &ChatWindow::onFriendRemoveNotify);
    connect(net, &NetworkManager::friendListReceived,     this, &ChatWindow::onFriendListReceived);
    connect(net, &NetworkManager::friendPendingReceived,  this, &ChatWindow::onFriendPendingReceived);
    connect(net, &NetworkManager::friendChatMessageReceived, this, &ChatWindow::onFriendChatMessage);
    connect(net, &NetworkManager::friendChatSendResponse,
            this, &ChatWindow::handleFriendSendResponse);
    connect(net, &NetworkManager::friendHistoryReceived,  this, &ChatWindow::onFriendHistoryReceived);
    connect(net, &NetworkManager::friendFileNotify,       this, &ChatWindow::onFriendFileNotify);
    connect(net, &NetworkManager::friendOnlineNotify,     this, &ChatWindow::onFriendOnlineNotify);
    connect(net, &NetworkManager::friendOfflineNotify,    this, &ChatWindow::onFriendOfflineNotify);
    connect(net, &NetworkManager::friendReadNotify,       this, &ChatWindow::onFriendReadNotify);
    connect(net, &NetworkManager::friendFileUploadStartResponse, this, &ChatWindow::onFriendFileUploadStartResponse);
    connect(net, &NetworkManager::friendRecallResponse, this, &ChatWindow::onFriendRecallResponse);
    connect(net, &NetworkManager::friendRecallNotify,   this, &ChatWindow::onFriendRecallNotify);

    // 单击文件消息：触发下载 / 暂停 / 恢复（仅点击气泡区域时生效）
    connect(m_messageView, &QListView::clicked, this, [this](const QModelIndex &idx) {
        // 检查点击位置是否在气泡区域
        QPoint pos = m_messageView->viewport()->mapFromGlobal(QCursor::pos());
        QStyleOptionViewItem opt;
        opt.rect = m_messageView->visualRect(idx);
        opt.font = m_messageView->font();
        QRect bubble = m_delegate->bubbleRectForIndex(opt, idx);
        if (!bubble.contains(pos)) return;

        int contentType = idx.data(MessageModel::ContentTypeRole).toInt();
        if (contentType != static_cast<int>(Message::File)) return;

        int fileId = idx.data(MessageModel::FileIdRole).toInt();
        bool fileCleared = idx.data(MessageModel::FileClearedRole).toBool();
        int dlState = idx.data(MessageModel::DownloadStateRole).toInt();
        bool cached = FileCache::instance()->isCached(fileId);

        // 上传中 → 暂停上传（fileId 为负数的临时消息）
        if (dlState == Message::Uploading) {
            pauseUpload();
            return;
        }
        // 上传暂停 → 恢复上传
        if (dlState == Message::UploadPaused) {
            resumeUpload();
            return;
        }

        // 以下操作需要有效 fileId（正数=房间文件，负数=好友文件）
        if (fileId == 0) return;
        if (fileCleared && !cached) {
            QMessageBox::information(this, "提示", "文件已过期或被清除，无法下载");
            return;
        }

        // 已缓存 → 不响应单击（双击打开）
        if (cached) return;

        // 下载中 → 暂停下载
        if (dlState == Message::Downloading) {
            pauseDownload(fileId);
            return;
        }
        // 下载暂停 → 恢复下载
        if (dlState == Message::Paused) {
            resumeDownload(fileId);
            return;
        }

        QString fileName = idx.data(MessageModel::FileNameRole).toString();
        qint64 fileSize  = idx.data(MessageModel::FileSizeRole).toLongLong();

        // 未下载 → 触发下载
        triggerFileDownload(fileId, fileName, fileSize);
    });

    // 双击：点击气泡打开文件，点击别人头像查看用户信息
    connect(m_messageView, &QListView::doubleClicked, this, [this](const QModelIndex &idx) {
        QPoint pos = m_messageView->viewport()->mapFromGlobal(QCursor::pos());
        QStyleOptionViewItem opt;
        opt.rect = m_messageView->visualRect(idx);
        opt.font = m_messageView->font();

        // 双击头像 → 查看用户信息（自己和他人均可）
        {
            QRect avatarRect = m_delegate->avatarRectForIndex(opt, idx);
            if (avatarRect.contains(pos)) {
                QString sender = idx.data(MessageModel::SenderRole).toString();
                QString senderName = idx.data(MessageModel::SenderNameRole).toString();
                showUserInfoDialog(sender, senderName);
                return;
            }
        }

        // 双击气泡 → 打开已缓存文件
        QRect bubble = m_delegate->bubbleRectForIndex(opt, idx);
        if (!bubble.contains(pos)) return;

        int contentType = idx.data(MessageModel::ContentTypeRole).toInt();
        if (contentType != static_cast<int>(Message::File)) return;

        int fileId = idx.data(MessageModel::FileIdRole).toInt();
        bool fileCleared = idx.data(MessageModel::FileClearedRole).toBool();
        bool cached = FileCache::instance()->isCached(fileId);
        if (fileCleared && !cached) {
            QMessageBox::information(this, "提示", "文件已过期或被清除，无法打开");
            return;
        }
        if (cached) {
            FileCache::openWithSystem(FileCache::instance()->cachedFilePath(fileId));
        }
    });
}

// ==================== 房间操作 ====================

void ChatWindow::requestRoomList() {
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_LIST_REQ));
}

void ChatWindow::onCreateRoom() {
    QInputDialog dialog(this);
    dialog.setInputMode(QInputDialog::TextInput);
    auto refreshText = [this, &dialog] {
        const auto &copy = WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale());
        dialog.setWindowTitle(copy.mainCreateRoomTitle);
        dialog.setLabelText(copy.mainCreateRoomPrompt);
        dialog.setOkButtonText(copy.mainNavigationCreateRoom);
        dialog.setCancelButtonText(copy.cancel);
        dialog.setAccessibleName(copy.mainCreateRoomAccessible);
    };
    connect(m_windowsLocaleViewModel, &WindowsLocaleViewModel::changed,
            &dialog, refreshText);
    refreshText();
    if (dialog.exec() != QDialog::Accepted) return;
    const QString name = dialog.textValue();
    if (name.trimmed().isEmpty()) return;

    NetworkManager::instance()->sendMessage(Protocol::makeCreateRoomReq(name.trimmed()));
}

void ChatWindow::onSearchRoom() {
    auto *net = NetworkManager::instance();
    RoomSearchDialog dialog(m_windowsLocaleViewModel, this);
    connect(&dialog, &RoomSearchDialog::searchRequested, &dialog,
            [net](const QString &keyword) {
        QJsonObject data;
        data["keyword"] = keyword;
        net->sendMessage(Protocol::makeMessage(Protocol::MsgType::ROOM_SEARCH_REQ, data));
    });
    connect(&dialog, &RoomSearchDialog::joinRequested, &dialog,
            [net](int roomId) {
        net->sendMessage(Protocol::makeJoinRoomReq(roomId));
    });
    connect(&dialog, &RoomSearchDialog::roomAvatarRequested, &dialog,
            [net](int roomId) {
        QJsonObject data;
        data["roomId"] = roomId;
        net->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::ROOM_AVATAR_GET_REQ, data));
    });
    connect(net, &NetworkManager::roomSearchResponse, &dialog,
            [this, &dialog](bool success, const QJsonArray &rooms,
                            const QString &error) {
        if (!success) {
            dialog.showFailure(error);
            return;
        }
        QVector<RoomSearchDialog::Result> results;
        results.reserve(rooms.size());
        for (const QJsonValue &value : rooms) {
            const QJsonObject room = value.toObject();
            RoomSearchDialog::Result result;
            result.roomId = room["roomId"].toInt();
            result.roomName = room["roomName"].toString();
            result.memberCount = room["memberCount"].toInt();
            bool alreadyJoined = false;
            for (int i = 0; i < m_roomList->count(); ++i) {
                if (m_roomList->item(i)->data(Qt::UserRole).toInt()
                        == result.roomId) {
                    alreadyJoined = true;
                    break;
                }
            }
            result.alreadyJoined = alreadyJoined;
            if (m_roomAvatarCache.contains(result.roomId)) {
                result.avatar = m_roomAvatarCache.value(result.roomId);
            } else {
                result.avatar = generateDefaultAvatar(
                    result.roomName, result.roomId);
                result.avatarNeedsRefresh = true;
            }
            results.push_back(std::move(result));
        }
        dialog.showResults(results);
    });
    connect(net, &NetworkManager::roomAvatarGetResponse, &dialog,
            [&dialog](int roomId, bool success, const QByteArray &avatarData) {
        if (!success || avatarData.isEmpty()) return;
        QPixmap avatar;
        if (avatar.loadFromData(avatarData))
            dialog.updateRoomAvatar(roomId, avatar);
    });
    dialog.exec();
}

void ChatWindow::onRoomCreated(bool success, int roomId, const QString &name, const QString &error) {
    if (success) {
        auto *item = new QListWidgetItem(name);
        item->setData(Qt::UserRole, roomId);
        m_roomList->addItem(item);
        switchRoom(roomId);
    } else {
        QMessageBox::warning(this, "创建失败", error);
    }
}

void ChatWindow::onRoomJoined(bool success, int roomId, const QString &name, const QString &error, bool newJoin) {
    if (success) {
        // 检查是否已在列表中
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId)
                goto found;
        }
        {
            auto *item = new QListWidgetItem(name);
            item->setData(Qt::UserRole, roomId);
            m_roomList->addItem(item);
        }
found:
        switchRoom(roomId);

        // 仅在用户真正首次加入时显示提示（由服务端判断）
        if (newJoin) {
            m_joinedRooms.insert(roomId);
            Message sysMsg = Message::createSystemMessage(roomId,
                QString("你加入了聊天室 %1").arg(name));
            getOrCreateModel(roomId)->addMessage(sysMsg);
        } else {
            m_joinedRooms.insert(roomId);
        }
    } else {
        QMessageBox::warning(this, "加入失败", error);
    }
}

void ChatWindow::onRoomListReceived(const QJsonArray &rooms) {
    m_roomList->clear();
    m_roomUnread.clear();
    QSet<int> allowedRoomIds;
    QSet<QString> allowedRoomKeys;
    for (const QJsonValue &v : rooms) {
        QJsonObject r = v.toObject();
        int id = r["roomId"].toInt();
        allowedRoomIds.insert(id);
        allowedRoomKeys.insert(QString::number(id));
        QString name = r["roomName"].toString();
        int unread = r["unread"].toInt(0);
        if (unread > 0)
            m_roomUnread[id] = unread;
        auto *item = new QListWidgetItem(name);
        item->setData(Qt::UserRole, id);
        // 显示已缓存的头像，否则显示默认头像
        if (m_roomAvatarCache.contains(id)) {
            item->setIcon(makeStableIcon(m_roomAvatarCache[id]));
        } else {
            item->setIcon(makeStableIcon(generateDefaultAvatar(name, id)));
        }
        m_roomList->addItem(item);
        // 请求聊天室头像
        if (!m_roomAvatarCache.contains(id)) {
            QJsonObject reqData;
            reqData["roomId"] = id;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::ROOM_AVATAR_GET_REQ, reqData));
        }
    }

    if (m_localRepository && !m_localRepository->pruneConversations(
            m_username, LocalConversationRepository::Kind::Room, allowedRoomKeys)) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=prune-rooms outcome=degraded detail=%1")
            .arg(m_localRepository->lastError());
    }
    const QList<int> cachedRoomIds = m_models.keys();
    for (int roomId : cachedRoomIds) {
        if (allowedRoomIds.contains(roomId)) continue;
        if (m_currentRoomId == roomId) {
            m_currentRoomId = -1;
            m_messageView->setModel(nullptr);
            refreshConversationShellText();
            m_userList->clear();
            m_restoringDraft = true;
            m_inputEdit->clear();
            m_restoringDraft = false;
        }
        delete m_models.take(roomId);
        m_conversationSyncService->forget(roomConversation(roomId));
        m_roomDrafts.remove(roomId);
    }
    for (int index = m_attachmentQueue.size() - 1; index >= 0; --index) {
        const auto &command = m_attachmentQueue[index];
        if (command.target.kind == LocalConversationRepository::Kind::Room
            && !allowedRoomIds.contains(command.target.roomId)) {
            m_queuedAttachmentIds.remove(command.clientMessageId);
            m_attachmentQueue.removeAt(index);
        }
    }
    if (m_upload.kind == LocalConversationRepository::Kind::Room
        && !m_upload.clientMessageId.isEmpty()
        && !allowedRoomIds.contains(m_upload.roomId)) {
        cancelUpload();
    }

    // 如果之前已在某个房间，恢复到该房间
    if (m_currentRoomId > 0) {
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == m_currentRoomId) {
                NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(m_currentRoomId));
                break;
            }
        }
    }
    retryPendingRoomSends(allowedRoomIds);
    const auto attachmentCommands = m_attachmentOutboxService->recoverRooms(
        m_username, allowedRoomIds);
    if (!m_attachmentOutboxService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=recover-rooms outcome=degraded detail=%1")
            .arg(m_attachmentOutboxService->lastError());
    }
    enqueueAttachments(attachmentCommands);
    updateUnreadDots();
}

void ChatWindow::onRoomSelected(QListWidgetItem *item) {
    if (m_isFriendChat) flushCurrentDraft();
    // 切回房间模式
    m_isFriendChat = false;
    m_currentFriendUsername.clear();
    m_currentFriendDisplayName.clear();
    m_currentFriendshipId = -1;
    m_friendList->clearSelection();
    if (m_rightPanel) m_rightPanel->show();

    int roomId = item->data(Qt::UserRole).toInt();
    if (roomId != m_currentRoomId) {
        // 先加入该房间
        NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(roomId));
    }
}

void ChatWindow::updateRoomListAvatars() {
    for (int i = 0; i < m_roomList->count(); ++i) {
        auto *item = m_roomList->item(i);
        int id = item->data(Qt::UserRole).toInt();
        if (m_roomAvatarCache.contains(id)) {
            item->setIcon(makeStableIcon(m_roomAvatarCache[id]));
        } else {
            item->setIcon(makeStableIcon(generateDefaultAvatar(item->text(), id)));
        }
    }
}

void ChatWindow::updateUnreadDots() {
    // 房间列表红点
    int totalRoomUnread = 0;
    for (int i = 0; i < m_roomList->count(); ++i) {
        auto *item = m_roomList->item(i);
        int roomId = item->data(Qt::UserRole).toInt();
        int cnt = m_roomUnread.value(roomId, 0);
        item->setData(UnreadRole, cnt);
        totalRoomUnread += cnt;
    }
    // 好友列表红点
    int totalFriendUnread = 0;
    for (int i = 0; i < m_friendList->count(); ++i) {
        auto *item = m_friendList->item(i);
        QString uname = item->data(Qt::UserRole).toString();
        int cnt = m_friendUnread.value(uname, 0);
        item->setData(UnreadRole, cnt);
        totalFriendUnread += cnt;
    }
    // 更新标签按钮（灰色文字 + 红色圆点）
    QString tabBase = "QPushButton { font-weight: bold; font-size: 13px; padding: 6px; border: none; border-bottom: 2px solid #4CAF50; }"
                      "QPushButton:!checked { border-bottom: 2px solid transparent; color: #888; }";
    m_tabRoomBtn->setStyleSheet(tabBase);
    m_tabFriendBtn->setStyleSheet(tabBase);
    // 红点显示/隐藏，定位在文字右侧
    auto positionDot = [](QLabel *dot, QPushButton *btn) {
        QFontMetrics fm(btn->font());
        int textW = fm.horizontalAdvance(btn->text());
        int cx = (btn->width() + textW) / 2 + 4;
        int cy = btn->height() / 2 - 4;
        dot->move(cx, cy);
    };
    m_tabRoomDot->setVisible(totalRoomUnread > 0);
    if (totalRoomUnread > 0) positionDot(m_tabRoomDot, m_tabRoomBtn);
    bool friendDot = totalFriendUnread > 0 || m_hasPendingFriendReq;
    m_tabFriendDot->setVisible(friendDot);
    if (friendDot) positionDot(m_tabFriendDot, m_tabFriendBtn);
    // 更新好友申请按钮
    m_friendReqBtn->setText(WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale()).mainNavigationFriendRequests);
    m_friendReqBtn->setStyleSheet("");
    m_friendReqDot->setVisible(m_hasPendingFriendReq);
    if (m_hasPendingFriendReq) positionDot(m_friendReqDot, m_friendReqBtn);
}

void ChatWindow::switchRoom(int roomId) {
    flushCurrentDraft();
    m_isFriendChat = false;
    m_currentFriendUsername.clear();
    m_currentFriendDisplayName.clear();
    m_currentFriendshipId = -1;
    m_currentRoomId = roomId;
    m_roomUnread.remove(roomId);
    updateUnreadDots();

    // 通知服务器标记已读
    {
        QJsonObject markData;
        markData["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::MARK_ROOM_READ, markData));
    }

    // 获取或创建模型
    MessageModel *model = getOrCreateModel(roomId);
    restoreCurrentDraft();

    // 临时禁用视图更新，防止切换房间时的闪烁
    m_messageView->setUpdatesEnabled(false);
    m_messageView->setModel(model);

    // 更新房间标题
    for (int i = 0; i < m_roomList->count(); ++i) {
        auto *item = m_roomList->item(i);
        if (item->data(Qt::UserRole).toInt() == roomId) {
            m_roomList->setCurrentItem(item);
            break;
        }
    }
    refreshConversationShellText();
    m_roomSettingsBtn->setVisible(true);

    // 请求用户列表和历史消息
    QJsonObject userData;
    userData["roomId"] = roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::USER_LIST_REQ, userData));

    const qint64 cursor = m_conversationSyncService->cursor(
        roomConversation(roomId));
    if (cursor > 0) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeHistoryAfterSequenceReq(roomId, cursor));
    } else {
        NetworkManager::instance()->sendMessage(Protocol::makeHistoryReq(roomId, 50));
    }

    // 请求房间设置（文件大小上限等）
    {
        QJsonObject settingsReq;
        settingsReq["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, settingsReq));
    }

    // 滚动到底部并恢复视图更新（使用 0ms 定时器等待布局完成）
    QTimer::singleShot(0, [this] {
        if (m_messageView->model() && m_messageView->model()->rowCount() > 0)
            m_messageView->scrollToBottom();
        m_messageView->setUpdatesEnabled(true);
    });
}

MessageModel *ChatWindow::getOrCreateModel(int roomId) {
    if (!m_models.contains(roomId)) {
        auto *model = new MessageModel(this);
        m_models[roomId] = model;
        if (!m_username.isEmpty()) {
            const auto snapshot = m_conversationSyncService->hydrate(
                roomConversation(roomId));
            QList<Message> cached = snapshot.messages;
            for (Message &message : cached) {
                message.setIsMine(message.sender() == m_username);
                if (message.fileId() > 0 && FileCache::instance()->isCached(message.fileId())) {
                    message.setDownloadState(Message::Downloaded);
                    message.setDownloadProgress(1.0);
                }
            }
            if (!cached.isEmpty()) model->prependMessages(cached);
            m_roomDrafts[roomId] = snapshot.draft;
        }
    }
    return m_models[roomId];
}

void ChatWindow::advanceRoomSyncCursor(int roomId, qint64 sequence) {
    if (roomId > 0)
        m_conversationSyncService->advance(roomConversation(roomId), sequence);
}

void ChatWindow::persistRoomSnapshot(int roomId) {
    if (m_username.isEmpty() || !m_models.contains(roomId)) return;
    if (!m_conversationSyncService->replace(
            roomConversation(roomId), m_models.value(roomId)->messages())) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=persist-room outcome=degraded roomId=%1 detail=%2")
            .arg(roomId).arg(m_conversationSyncService->lastError());
    }
}

void ChatWindow::persistRoomMessage(int roomId, const Message &message) {
    if (m_username.isEmpty()) return;
    if (!m_conversationSyncService->upsert(roomConversation(roomId), message)) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=upsert-room outcome=degraded roomId=%1 detail=%2")
            .arg(roomId).arg(m_conversationSyncService->lastError());
    }
}

void ChatWindow::removeCachedRoom(int roomId) {
    m_roomDrafts.remove(roomId);
    if (m_username.isEmpty()) return;
    if (!m_conversationSyncService->remove(roomConversation(roomId))) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=remove-room outcome=degraded roomId=%1 detail=%2")
            .arg(roomId).arg(m_conversationSyncService->lastError());
    }
}

void ChatWindow::requestCurrentRoomResume() {
    if (m_currentRoomId <= 0) return;
    getOrCreateModel(m_currentRoomId);
    const qint64 cursor = m_conversationSyncService->cursor(
        roomConversation(m_currentRoomId));
    if (cursor > 0) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeHistoryAfterSequenceReq(m_currentRoomId, cursor));
    } else {
        NetworkManager::instance()->sendMessage(
            Protocol::makeHistoryReq(m_currentRoomId, 50));
    }
}

void ChatWindow::advanceFriendSyncCursor(const QString &friendUsername, qint64 sequence) {
    if (!friendUsername.isEmpty())
        m_conversationSyncService->advance(
            friendConversation(friendUsername), sequence);
}

void ChatWindow::persistFriendSnapshot(const QString &friendUsername) {
    if (m_username.isEmpty() || friendUsername.isEmpty()
        || !m_friendModels.contains(friendUsername)) return;
    m_friendModels.value(friendUsername)->applyPeerReadWatermark(
        m_friendReadWatermarks.value(friendUsername, 0));
    if (!m_conversationSyncService->replace(
            friendConversation(friendUsername),
            m_friendModels.value(friendUsername)->messages())) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=persist-direct outcome=degraded peer=%1 detail=%2")
            .arg(friendUsername, m_conversationSyncService->lastError());
    }
}

void ChatWindow::persistFriendMessage(const QString &friendUsername,
                                      const Message &message) {
    if (m_username.isEmpty() || friendUsername.isEmpty()) return;
    if (!m_conversationSyncService->upsert(
            friendConversation(friendUsername), message)) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=upsert-direct outcome=degraded peer=%1 detail=%2")
            .arg(friendUsername, m_conversationSyncService->lastError());
    }
}

void ChatWindow::removeCachedFriend(const QString &friendUsername) {
    m_friendDrafts.remove(friendUsername);
    if (m_username.isEmpty() || friendUsername.isEmpty()) return;
    if (!m_conversationSyncService->remove(friendConversation(friendUsername))) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=remove-direct outcome=degraded peer=%1 detail=%2")
            .arg(friendUsername, m_conversationSyncService->lastError());
    }
}

QString ChatWindow::friendConversationKey(const QString &friendUsername) const {
    const int friendshipId = m_friendshipIds.value(friendUsername, 0);
    return friendshipId > 0 ? QString::number(friendshipId)
                            : QStringLiteral("peer:%1").arg(friendUsername);
}

ConversationSyncService::ConversationRef ChatWindow::roomConversation(
    int roomId) const {
    return {LocalConversationRepository::Kind::Room, QString::number(roomId)};
}

ConversationSyncService::ConversationRef ChatWindow::friendConversation(
    const QString &friendUsername) const {
    return {LocalConversationRepository::Kind::Direct,
            friendConversationKey(friendUsername)};
}

void ChatWindow::flushCurrentDraft() {
    if (m_restoringDraft || !m_inputEdit) return;
    if (m_draftSaveTimer) m_draftSaveTimer->stop();
    const QString draft = m_inputEdit->toPlainText()
        .left(LocalConversationRepository::MaxDraftLength);
    if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
        m_friendDrafts[m_currentFriendUsername] = draft;
        if (m_localRepository && !m_localRepository->saveDraft(
                m_username, LocalConversationRepository::Kind::Direct,
                friendConversationKey(m_currentFriendUsername), draft)) {
            qWarning().noquote() << QStringLiteral(
                "[LocalStore] operation=save-direct-draft outcome=degraded peer=%1 detail=%2")
                .arg(m_currentFriendUsername, m_localRepository->lastError());
        }
    } else if (m_currentRoomId > 0) {
        m_roomDrafts[m_currentRoomId] = draft;
        if (m_localRepository && !m_localRepository->saveDraft(
                m_username, LocalConversationRepository::Kind::Room,
                QString::number(m_currentRoomId), draft)) {
            qWarning().noquote() << QStringLiteral(
                "[LocalStore] operation=save-room-draft outcome=degraded roomId=%1 detail=%2")
                .arg(m_currentRoomId).arg(m_localRepository->lastError());
        }
    }
}

void ChatWindow::restoreCurrentDraft() {
    if (!m_inputEdit) return;
    if (m_draftSaveTimer) m_draftSaveTimer->stop();
    const QString draft = m_isFriendChat
        ? m_friendDrafts.value(m_currentFriendUsername)
        : m_roomDrafts.value(m_currentRoomId);
    m_restoringDraft = true;
    m_inputEdit->setPlainText(draft);
    m_inputEdit->moveCursor(QTextCursor::End);
    m_restoringDraft = false;
}

void ChatWindow::clearCurrentDraft() {
    if (m_draftSaveTimer) m_draftSaveTimer->stop();
    if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
        m_friendDrafts.remove(m_currentFriendUsername);
        if (m_localRepository) {
            m_localRepository->saveDraft(
                m_username, LocalConversationRepository::Kind::Direct,
                friendConversationKey(m_currentFriendUsername), {});
        }
    } else if (m_currentRoomId > 0) {
        m_roomDrafts.remove(m_currentRoomId);
        if (m_localRepository) {
            m_localRepository->saveDraft(
                m_username, LocalConversationRepository::Kind::Room,
                QString::number(m_currentRoomId), {});
        }
    }
    m_restoringDraft = true;
    m_inputEdit->clear();
    m_restoringDraft = false;
}

void ChatWindow::handleRoomSendResponse(const QJsonObject &data) {
    const int roomId = data["roomId"].toInt();
    const QString clientMessageId = data["clientMessageId"].toString();
    if (roomId <= 0 || clientMessageId.isEmpty()) return;
    MessageModel *model = getOrCreateModel(roomId);
    const int row = model->findMessageByClientMessageId(clientMessageId);
    if (row < 0) return;
    Message resolved = model->messageAt(row);
    const auto target = OutgoingMessageService::roomTarget(roomId);
    if (data["success"].toBool()) {
        const qint64 sequence = data["sequence"].toVariant().toLongLong();
        if (!m_outgoingMessageService->recordAccepted(
                m_username, target, &resolved, data["id"].toInt(), sequence,
                data["timestamp"].toVariant().toLongLong())) {
            qWarning().noquote() << QStringLiteral(
                "[Outbox] operation=accept-room outcome=degraded roomId=%1 detail=%2")
                .arg(roomId).arg(m_outgoingMessageService->lastError());
        }
        if (resolved.deliveryState() != Message::Accepted) return;
        advanceRoomSyncCursor(roomId, sequence);
    } else {
        if (!m_outgoingMessageService->recordFailed(
                m_username, target, &resolved,
                m_conversationSyncService->cursor(roomConversation(roomId)))) {
            qWarning().noquote() << QStringLiteral(
                "[Outbox] operation=reject-room outcome=degraded roomId=%1 detail=%2")
                .arg(roomId).arg(m_outgoingMessageService->lastError());
        }
        m_statusLabel->setText(data["error"].toString(QStringLiteral("消息发送失败")));
    }
    model->addMessage(resolved);
}

void ChatWindow::handleFriendSendResponse(const QJsonObject &data) {
    QString friendUsername = data["friendUsername"].toString();
    const int friendshipId = data["friendshipId"].toInt();
    if (friendUsername.isEmpty() && friendshipId > 0)
        friendUsername = m_friendshipIds.key(friendshipId);
    const QString clientMessageId = data["clientMessageId"].toString();
    if (friendUsername.isEmpty() || clientMessageId.isEmpty()) return;
    if (friendshipId > 0) m_friendshipIds[friendUsername] = friendshipId;
    MessageModel *model = getOrCreateFriendModel(friendUsername);
    const int row = model->findMessageByClientMessageId(clientMessageId);
    if (row < 0) return;
    Message resolved = model->messageAt(row);
    const auto target = OutgoingMessageService::directTarget(
        friendConversationKey(friendUsername), friendUsername);
    if (data["success"].toBool()) {
        const qint64 sequence = data["sequence"].toVariant().toLongLong();
        if (!m_outgoingMessageService->recordAccepted(
                m_username, target, &resolved, data["id"].toInt(), sequence,
                data["timestamp"].toVariant().toLongLong())) {
            qWarning().noquote() << QStringLiteral(
                "[Outbox] operation=accept-direct outcome=degraded peer=%1 detail=%2")
                .arg(friendUsername, m_outgoingMessageService->lastError());
        }
        if (resolved.deliveryState() != Message::Accepted) return;
        advanceFriendSyncCursor(friendUsername, sequence);
    } else {
        if (!m_outgoingMessageService->recordFailed(
                m_username, target, &resolved,
                m_conversationSyncService->cursor(
                    friendConversation(friendUsername)))) {
            qWarning().noquote() << QStringLiteral(
                "[Outbox] operation=reject-direct outcome=degraded peer=%1 detail=%2")
                .arg(friendUsername, m_outgoingMessageService->lastError());
        }
        m_statusLabel->setText(data["error"].toString(QStringLiteral("好友消息发送失败")));
    }
    model->addMessage(resolved);
}

void ChatWindow::dispatchOutgoing(
    const OutgoingMessageService::Command &command) {
    if (command.target.kind == LocalConversationRepository::Kind::Room) {
        NetworkManager::instance()->sendMessage(Protocol::makeChatMsg(
            command.target.roomId, m_username, command.content,
            command.contentType, command.clientMessageId));
    } else {
        NetworkManager::instance()->sendMessage(Protocol::makeFriendChatMsg(
            command.target.peerUsername, command.content,
            command.contentType, command.clientMessageId));
    }
}

bool ChatWindow::stageAttachment(
    const AttachmentOutboxService::Target &target, const QString &filePath,
    const QString &contentType) {
    AttachmentOutboxService::Command command;
    if (!m_attachmentOutboxService->stage(
            m_username, target, filePath, contentType, &command)) {
        QMessageBox::warning(
            this, QStringLiteral("文件发送"),
            QStringLiteral("无法安全保存发送任务：%1")
                .arg(m_attachmentOutboxService->lastError()));
        return false;
    }
    if (NetworkManager::instance()->isConnected()) {
        enqueueAttachments({command});
    } else {
        m_statusLabel->setText(
            QStringLiteral("文件任务已保存，连接恢复后发送"));
    }
    return true;
}

void ChatWindow::enqueueAttachments(
    const QList<AttachmentOutboxService::Command> &commands) {
    for (const auto &command : commands) {
        if (command.clientMessageId.isEmpty()
            || command.clientMessageId == m_upload.clientMessageId
            || m_queuedAttachmentIds.contains(command.clientMessageId)) continue;
        m_attachmentQueue.append(command);
        m_queuedAttachmentIds.insert(command.clientMessageId);
    }
    processNextAttachment();
}

void ChatWindow::processNextAttachment() {
    if (!NetworkManager::instance()->isConnected()
        || !m_upload.clientMessageId.isEmpty()) return;
    while (!m_attachmentQueue.isEmpty()) {
        const auto queued = m_attachmentQueue.takeFirst();
        m_queuedAttachmentIds.remove(queued.clientMessageId);
        AttachmentOutboxService::Command command;
        if (!m_attachmentOutboxService->prepareRetry(
                m_username, queued.target, queued.clientMessageId, &command)) {
            qWarning().noquote() << QStringLiteral(
                "[AttachmentOutbox] operation=prepare outcome=failed clientMessageId=%1 detail=%2")
                .arg(queued.clientMessageId,
                     m_attachmentOutboxService->lastError());
            m_statusLabel->setText(
                QStringLiteral("有文件任务需要重新选择源文件"));
            continue;
        }
        dispatchAttachment(command);
        return;
    }
}

void ChatWindow::dispatchAttachment(
    const AttachmentOutboxService::Command &command) {
    m_upload.filePath = command.sourcePath;
    m_upload.fileSize = command.fileSize;
    m_upload.offset = 0;
    m_upload.uploadId.clear();
    m_upload.clientMessageId = command.clientMessageId;
    m_upload.kind = command.target.kind;
    m_upload.conversationKey = command.target.conversationKey;
    m_upload.roomId = command.target.roomId;
    m_upload.peerUsername = command.target.peerUsername;
    m_upload.contentType = command.contentType;
    m_upload.thumbnailData.clear();
    m_upload.rawHttp = false;
    m_uploadPaused = false;
    m_uploadingFileName = command.fileName;

    static int s_tempRoomFileId = 0;
    static int s_tempFriendFileId = -10000;
    m_uploadingFileId = command.target.kind
        == LocalConversationRepository::Kind::Room
        ? --s_tempRoomFileId : --s_tempFriendFileId;

    Message uploadMsg;
    if (command.target.kind == LocalConversationRepository::Kind::Room) {
        uploadMsg = Message::createFileMessage(
            command.target.roomId, m_username, command.fileName,
            command.fileSize, m_uploadingFileId);
    } else {
        uploadMsg.setSender(m_username);
        uploadMsg.setFileName(command.fileName);
        uploadMsg.setFileSize(command.fileSize);
        uploadMsg.setFileId(m_uploadingFileId);
        uploadMsg.setContentType(Message::File);
        uploadMsg.setTimestamp(QDateTime::currentMSecsSinceEpoch());
    }
    uploadMsg.setSenderName(m_displayName);
    uploadMsg.setIsMine(true);
    uploadMsg.setClientMessageId(command.clientMessageId);
    uploadMsg.setDeliveryState(Message::Sending);
    uploadMsg.setDownloadState(Message::Uploading);
    uploadMsg.setDownloadProgress(0.0);
    if (command.target.kind == LocalConversationRepository::Kind::Room) {
        getOrCreateModel(command.target.roomId)->addMessage(uploadMsg);
    } else {
        getOrCreateFriendModel(command.target.peerUsername)->addMessage(uploadMsg);
    }

    m_pendingSentFiles[command.fileName] = command.sourcePath;
    m_pendingSentFilesByClientId[command.clientMessageId] = command.sourcePath;

    static const QStringList videoExtensions = {
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    if (videoExtensions.contains(QFileInfo(command.fileName).suffix().toLower())) {
        m_upload.thumbnailData = generateVideoThumbnailData(command.sourcePath);
        if (!m_upload.thumbnailData.isEmpty()) {
            const QString thumbnailPath = FileCache::instance()->thumbDir()
                + QString("/thumb_%1.jpg").arg(m_uploadingFileId);
            QFile thumbnail(thumbnailPath);
            if (thumbnail.open(QIODevice::WriteOnly))
                thumbnail.write(m_upload.thumbnailData);
        }
    }

    QJsonObject data;
    data["fileName"] = command.fileName;
    data["fileSize"] = static_cast<double>(command.fileSize);
    data["clientMessageId"] = command.clientMessageId;
    if (command.target.kind == LocalConversationRepository::Kind::Room) {
        data["roomId"] = command.target.roomId;
        NetworkManager::instance()->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FILE_UPLOAD_START, data));
    } else {
        data["friendUsername"] = command.target.peerUsername;
        NetworkManager::instance()->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FRIEND_FILE_UPLOAD_START, data));
    }
    m_statusLabel->setText(QString("准备上传: %1 (%2)")
        .arg(command.fileName)
        .arg(QLocale().formattedDataSize(command.fileSize)));
}

void ChatWindow::failActiveAttachment(
    const QString &failureCode, const QString &message) {
    const QString clientMessageId = m_upload.clientMessageId;
    if (m_upload.rawHttp && !m_upload.uploadId.isEmpty())
        NetworkManager::instance()->cancelRawUpload(m_upload.uploadId);
    if (!m_upload.uploadId.isEmpty()) {
        QJsonObject data;
        data["uploadId"] = m_upload.uploadId;
        NetworkManager::instance()->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FILE_UPLOAD_CANCEL, data));
    }
    if (!clientMessageId.isEmpty()
        && !m_attachmentOutboxService->recordFailed(
            m_username, clientMessageId, failureCode)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=fail outcome=degraded clientMessageId=%1 detail=%2")
            .arg(clientMessageId, m_attachmentOutboxService->lastError());
    }
    clearUploadState(true);
    m_statusLabel->setText(message);
}

void ChatWindow::retryPendingRoomSends(const QSet<int> &allowedRoomIds) {
    const auto commands = m_outgoingMessageService->recoverRooms(
        m_username, allowedRoomIds);
    if (!m_outgoingMessageService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[Outbox] operation=recover-rooms outcome=degraded detail=%1")
            .arg(m_outgoingMessageService->lastError());
    }
    for (const auto &command : commands) dispatchOutgoing(command);
}

void ChatWindow::retryPendingFriendSends() {
    QMap<QString, QString> peerByConversationKey;
    for (auto it = m_friendshipIds.cbegin(); it != m_friendshipIds.cend(); ++it) {
        peerByConversationKey.insert(QString::number(it.value()), it.key());
        peerByConversationKey.insert(QStringLiteral("peer:%1").arg(it.key()), it.key());
    }
    const auto commands = m_outgoingMessageService->recoverDirects(
        m_username, peerByConversationKey);
    if (!m_outgoingMessageService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[Outbox] operation=recover-directs outcome=degraded detail=%1")
            .arg(m_outgoingMessageService->lastError());
    }
    for (const auto &command : commands) dispatchOutgoing(command);

    const auto attachmentCommands = m_attachmentOutboxService->recoverDirects(
        m_username, peerByConversationKey);
    if (!m_attachmentOutboxService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=recover-directs outcome=degraded detail=%1")
            .arg(m_attachmentOutboxService->lastError());
    }
    enqueueAttachments(attachmentCommands);
}

void ChatWindow::requestCurrentFriendResume() {
    if (!m_isFriendChat || m_currentFriendUsername.isEmpty()) return;
    getOrCreateFriendModel(m_currentFriendUsername);
    const qint64 cursor = m_conversationSyncService->cursor(
        friendConversation(m_currentFriendUsername));
    if (cursor > 0) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeFriendHistoryAfterSequenceReq(m_currentFriendUsername, cursor));
    } else {
        QJsonObject data;
        data["friendUsername"] = m_currentFriendUsername;
        data["count"] = 50;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_REQ, data));
    }
}

// ==================== 消息处理 ====================

void ChatWindow::onSendMessage() {
    QString text = m_inputEdit->toPlainText().trimmed();
    if (text.isEmpty()) return;

    if (m_isFriendChat) {
        // 好友私聊模式
        if (m_currentFriendUsername.isEmpty()) return;
        OutgoingMessageService::StagedSend send;
        const auto target = OutgoingMessageService::directTarget(
            friendConversationKey(m_currentFriendUsername), m_currentFriendUsername);
        if (!m_outgoingMessageService->stage(
                m_username, target, m_username, m_displayName, text,
                Message::Text,
                m_conversationSyncService->cursor(
                    friendConversation(m_currentFriendUsername)), &send)) {
            m_statusLabel->setText(QStringLiteral("无法准备发送消息"));
            return;
        }
        if (!m_outgoingMessageService->lastError().isEmpty()) {
            qWarning().noquote() << QStringLiteral(
                "[Outbox] operation=stage-direct outcome=degraded peer=%1 detail=%2")
                .arg(m_currentFriendUsername,
                     m_outgoingMessageService->lastError());
        }
        getOrCreateFriendModel(m_currentFriendUsername)->addMessage(send.message);
        dispatchOutgoing(send.command);
        clearCurrentDraft();
        return;
    }

    if (m_currentRoomId < 0) {
        QMessageBox::information(this, "提示", "请先加入一个聊天室");
        return;
    }

    OutgoingMessageService::StagedSend send;
    const auto target = OutgoingMessageService::roomTarget(m_currentRoomId);
    if (!m_outgoingMessageService->stage(
            m_username, target, m_username, m_displayName, text, Message::Text,
            m_conversationSyncService->cursor(
                roomConversation(m_currentRoomId)), &send)) {
        m_statusLabel->setText(QStringLiteral("无法准备发送消息"));
        return;
    }
    if (!m_outgoingMessageService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[Outbox] operation=stage-room outcome=degraded roomId=%1 detail=%2")
            .arg(m_currentRoomId).arg(m_outgoingMessageService->lastError());
    }
    getOrCreateModel(m_currentRoomId)->addMessage(send.message);
    dispatchOutgoing(send.command);

    clearCurrentDraft();
}

void ChatWindow::onChatMessage(const QJsonObject &msg) {
    Message message = Message::fromJson(msg);
    message.setIsMine(message.sender() == m_username);

    int roomId = message.roomId();
    MessageModel *model = getOrCreateModel(roomId);
    model->addMessage(message);
    advanceRoomSyncCursor(roomId, message.sequence());
    persistRoomMessage(roomId, message);

    // 如果是当前房间，滚动到底部
    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
    } else {
        // 非当前房间，增加未读计数
        m_roomUnread[roomId] = m_roomUnread.value(roomId, 0) + 1;
        updateUnreadDots();
    }

    // 如果窗口不在前台，发送通知
    if (!isActiveWindow() && m_trayManager) {
        m_trayManager->showNotification(
            message.sender(),
            message.recalled() ? "消息已撤回" : message.content());
    }
}

void ChatWindow::onSystemMessage(const QJsonObject &msg) {
    Message message = Message::fromJson(msg);
    message.setIsMine(false);
    message.setContentType(Message::System);
    if (message.sender().isEmpty())
        message.setSender(QStringLiteral("System"));

    int roomId = message.roomId();
    MessageModel *model = getOrCreateModel(roomId);
    model->addMessage(message);
    advanceRoomSyncCursor(roomId, message.sequence());
    persistRoomMessage(roomId, message);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
        // 系统消息可能涉及管理员变更等，刷新用户列表以确保实时更新
        QJsonObject userData;
        userData["roomId"] = m_currentRoomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::USER_LIST_REQ, userData));
    }
}

void ChatWindow::onHistoryReceived(const QJsonObject &data) {
    const auto page = V1HistoryPageAdapter::parseRoom(data, m_username);
    if (!page.valid) {
        qWarning().noquote() << QStringLiteral(
            "[Sync] operation=parse-room-history outcome=rejected code=%1 detail=%2")
            .arg(page.errorCode, page.error);
        m_statusLabel->setText(page.error.isEmpty()
            ? QStringLiteral("聊天记录同步失败") : page.error);
        return;
    }
    const int roomId = page.roomId;
    MessageModel *model = getOrCreateModel(roomId);
    QList<Message> messages = page.messages;
    QList<PendingHistoryDownload> pendingDownloads;
    for (Message &message : messages) {
        PendingHistoryDownload download;
        if (prepareHistoryMedia(&message, &download))
            pendingDownloads.append(download);
    }

    bool isCurrent = (roomId == m_currentRoomId);
    if (isCurrent)
        m_messageView->setUpdatesEnabled(false);

    if (page.sequenceMode) model->reconcileSyncPage(messages, page.events);
    else model->prependMessages(messages);

    const auto progress = m_conversationSyncService->applyPage(
        roomConversation(roomId), page.sequenceMode, page.observedSequences,
        page.nextSequence, page.hasMore);
    if (!m_conversationSyncService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[Sync] operation=advance-room-history outcome=stopped roomId=%1 detail=%2")
            .arg(roomId).arg(m_conversationSyncService->lastError());
        m_statusLabel->setText(QStringLiteral("聊天记录续传已停止，可重新进入会话重试"));
    }
    persistRoomSnapshot(roomId);
    if (progress.requestNext) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeHistoryAfterSequenceReq(roomId, progress.cursor));
    }

    if (isCurrent) {
        QTimer::singleShot(0, [this] {
            m_messageView->scrollToBottom();
            m_messageView->setUpdatesEnabled(true);
        });
    }

    // 自动下载历史中未缓存的图片
    for (const auto &download : pendingDownloads) {
        if (model->findMessageByFileId(download.fileId) >= 0
            && !FileCache::instance()->isCached(download.fileId)) {
            triggerFileDownload(download.fileId, download.fileName,
                                download.fileSize);
        }
    }
}

// ==================== 用户列表 ====================

void ChatWindow::onUserListReceived(int roomId, const QJsonArray &users) {
    if (roomId == m_currentRoomId) {
        m_userList->clear();
        for (const QJsonValue &v : users) {
            QJsonObject userObj = v.toObject();
            QString username = userObj["username"].toString();
            QString displayName = userObj["displayName"].toString();
            if (displayName.isEmpty()) displayName = username;
            bool isAdmin = userObj["isAdmin"].toBool();
            bool isOnline = userObj["isOnline"].toBool();

            addUserListItem(username, displayName, isAdmin, isOnline);
        }
    }
}

void ChatWindow::onUserJoined(int roomId, const QString &username, const QString &displayName) {
    if (roomId == m_currentRoomId) {
        // 避免重复添加
        if (!findUserListItem(username)) {
            addUserListItem(username, displayName, false, true);
        }

        // 添加系统消息：成员加入聊天室
        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 加入了聊天室").arg(displayName));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

void ChatWindow::onUserLeft(int roomId, const QString &username, const QString &displayName) {
    if (roomId == m_currentRoomId) {
        // 从列表中移除
        QListWidgetItem *item = findUserListItem(username);
        if (item)
            delete m_userList->takeItem(m_userList->row(item));

        // 添加系统消息：成员退出聊天室
        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 退出了聊天室").arg(displayName));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

// ==================== 文件传输 ====================

void ChatWindow::onSendFile() {
    if (m_isFriendChat) { onSendFriendFile(); return; }
    if (m_currentRoomId < 0) return;

    QString filePath = QFileDialog::getOpenFileName(this, "选择文件");
    if (filePath.isEmpty()) return;

    QFileInfo fi(filePath);
    if (!fi.exists()) return;

    if (fi.size() > Protocol::MAX_LARGE_FILE) {
        QMessageBox::warning(this, "错误",
            QString("文件大小不能超过%1GB").arg(Protocol::MAX_LARGE_FILE / 1024 / 1024 / 1024));
        return;
    }

    qint64 roomLimit = m_roomMaxFileSize.value(m_currentRoomId, 10LL * 1024 * 1024 * 1024);
    if (roomLimit > 0 && fi.size() > roomLimit) {
        QMessageBox::warning(this, "错误",
            QString("文件大小超过房间上限(%1MB)").arg(roomLimit / 1024 / 1024));
        return;
    }

    startChunkedUpload(filePath);
}

void ChatWindow::onSendImage() {
    if (m_isFriendChat) { onSendFriendImage(); return; }
    if (m_currentRoomId < 0) return;

    QString filePath = QFileDialog::getOpenFileName(this, "选择图片",
        QString(), "图片 (*.png *.jpg *.jpeg *.gif *.bmp *.webp)");
    if (filePath.isEmpty()) return;

    QFileInfo fi(filePath);
    if (!fi.exists()) return;

    if (fi.size() > Protocol::MAX_SMALL_FILE) {
        QMessageBox::warning(this, "错误",
            QString("图片大小不能超过%1MB").arg(Protocol::MAX_SMALL_FILE / 1024 / 1024));
        return;
    }

    qint64 roomLimit = m_roomMaxFileSize.value(m_currentRoomId, 10LL * 1024 * 1024 * 1024);
    if (roomLimit > 0 && fi.size() > roomLimit) {
        QMessageBox::warning(this, "错误",
            QString("图片大小超过房间上限(%1MB)").arg(roomLimit / 1024 / 1024));
        return;
    }

    startChunkedUpload(filePath);
}

void ChatWindow::onFileNotify(const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    int fileId = data["fileId"].toInt();
    QString fileName = data["fileName"].toString();
    QString sender   = data["sender"].toString();
    QString senderName = data["senderName"].toString();
    const QString clientMessageId = data["clientMessageId"].toString();
    qint64 fSize = static_cast<qint64>(data["fileSize"].toDouble());

    Message msg = Message::createFileMessage(
        roomId, sender, fileName, fSize, fileId);
    msg.setId(data["id"].toInt());
    msg.setClientMessageId(clientMessageId);
    msg.setSequence(data["sequence"].toVariant().toLongLong());
    msg.setTimestamp(data["timestamp"].toVariant().toLongLong());
    msg.setSenderName(senderName);
    msg.setIsMine(sender == m_username);
    msg.setFileCleared(data["fileCleared"].toBool(false));
    msg.setClearReason(data["clearReason"].toString());

    // 判断是否为图片文件
    static const QStringList imgExts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    QString suffix = QFileInfo(fileName).suffix().toLower();
    bool isImage = imgExts.contains(suffix);
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    bool isVideo = vidExts.contains(suffix);

    // 接收到服务器转发的视频缩略图 → 保存到本地缓存
    if (isVideo && data.contains("thumbnail")) {
        QByteArray thumbData = QByteArray::fromBase64(data["thumbnail"].toString().toLatin1());
        if (!thumbData.isEmpty()) {
            QString tDir = FileCache::instance()->thumbDir();
            QString thumbPath = tDir + QString("/thumb_%1.jpg").arg(fileId);
            QFile tf(thumbPath);
            if (tf.open(QIODevice::WriteOnly)) {
                tf.write(thumbData);
                tf.close();
                qInfo() << "[VideoThumb] 从服务器接收缩略图已保存:" << thumbPath;
                QPixmapCache::remove(QString("vidthumb_%1").arg(fileId));
            }
        }
    }

    // 发送者自己的文件：直接从本地复制到缓存，无需下载
    QString sentLocalPath;
    if (sender == m_username && !clientMessageId.isEmpty())
        sentLocalPath = m_pendingSentFilesByClientId.take(clientMessageId);
    if (sentLocalPath.isEmpty() && sender == m_username
        && m_pendingSentFiles.contains(fileName)) {
        sentLocalPath = m_pendingSentFiles.take(fileName);
    }
    if (sender == m_username && !sentLocalPath.isEmpty()) {
        // 移除临时上传消息（大文件分块上传时存在临时消息）
        if (m_uploadingFileId != 0
            && (clientMessageId.isEmpty()
                || clientMessageId == m_upload.clientMessageId)) {
            getOrCreateModel(roomId)->removeMessageByFileId(m_uploadingFileId);
            // 清理临时缩略图
            QString tempThumb = FileCache::instance()->thumbDir()
                                + QString("/thumb_%1.jpg").arg(m_uploadingFileId);
            QFile::remove(tempThumb);
            QPixmapCache::remove(QString("vidthumb_%1").arg(m_uploadingFileId));
            m_uploadingFileId = 0;
            m_uploadingFileName.clear();
        }

        if (QFile::exists(sentLocalPath)) {
            QString cached = FileCache::instance()->cacheFromLocal(
                fileId, fileName, sentLocalPath);
            if (!cached.isEmpty()) {
                msg.setDownloadState(Message::Downloaded);
                msg.setDownloadProgress(1.0);
                // 发送者的视频缩略图已在发送时本地生成，
                // 如果上面的 thumbnail 字段不存在时再从视频生成
                if (isVideo) {
                    QString thumbPath = FileCache::instance()->thumbDir()
                                        + QString("/thumb_%1.jpg").arg(fileId);
                    if (!QFile::exists(thumbPath)) {
                        generateVideoThumbnail(fileId, cached);
                    }
                }
            }
        }
        if (!clientMessageId.isEmpty()) {
            m_attachmentOutboxService->complete(m_username, clientMessageId);
            if (clientMessageId == m_upload.clientMessageId)
                clearUploadState(false);
        }
    }

    // 已缓存则标记为已下载
    if (FileCache::instance()->isCached(fileId)) {
        msg.setDownloadState(Message::Downloaded);
        msg.setDownloadProgress(1.0);
    }

    getOrCreateModel(roomId)->addMessage(msg);
    advanceRoomSyncCursor(roomId, msg.sequence());
    persistRoomMessage(roomId, msg);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] { m_messageView->scrollToBottom(); });
    } else {
        m_roomUnread[roomId] = m_roomUnread.value(roomId, 0) + 1;
        updateUnreadDots();
    }

    // 仅图片文件自动下载缓存，其余文件需要用户点击下载
    if (isImage && !msg.fileCleared() && !FileCache::instance()->isCached(fileId)) {
        triggerFileDownload(fileId, fileName, fSize);
    }
}

void ChatWindow::onFileDownloadReady(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        int failId = data["fileId"].toInt();
        m_statusLabel->setText("文件下载失败: " + data["error"].toString());
        updateAllModelsDownloadProgress(failId, Message::NotDownloaded, 0.0);
        return;
    }

    // COS 文件：服务器返回外网 URL，使用浏览器下载
    if (data.contains("cosUrl") && !data["cosUrl"].toString().isEmpty()) {
        QDesktopServices::openUrl(QUrl(data["cosUrl"].toString()));
        return;
    }

    int fileId = data["fileId"].toInt();
    QString fileName = data["fileName"].toString();
    QByteArray fileData = QByteArray::fromBase64(data["fileData"].toString().toUtf8());
    onFileDownloadComplete(fileId, fileName, fileData);
}

// ==================== 大文件分块传输 ====================

void ChatWindow::startChunkedUpload(const QString &filePath) {
    const QFileInfo file(filePath);
    QString contentType = QStringLiteral("file");
    static const QStringList imageExtensions = {
        "png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList videoExtensions = {
        "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    if (imageExtensions.contains(file.suffix().toLower()))
        contentType = QStringLiteral("image");
    else if (videoExtensions.contains(file.suffix().toLower()))
        contentType = QStringLiteral("video");
    stageAttachment(AttachmentOutboxService::roomTarget(m_currentRoomId),
                    filePath, contentType);
}

void ChatWindow::onUploadStartResponse(const QJsonObject &data) {
    if (m_upload.clientMessageId.isEmpty()
        || m_upload.kind != LocalConversationRepository::Kind::Room)
        return;
    const QString responseClientId = data["clientMessageId"].toString();
    if (!responseClientId.isEmpty()
        && responseClientId != m_upload.clientMessageId) return;
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "上传失败", data["error"].toString());
        failActiveAttachment(data["errorCode"].toString(
                                 QStringLiteral("UPLOAD_START_REJECTED")),
                             QStringLiteral("文件上传失败，可稍后重试"));
        return;
    }
    m_upload.uploadId = data["uploadId"].toString();
    if (!data["clientMessageId"].toString().isEmpty())
        m_upload.clientMessageId = data["clientMessageId"].toString();
    if (!m_attachmentOutboxService->recordUploading(
            m_username, m_upload.clientMessageId)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=uploading outcome=degraded detail=%1")
            .arg(m_attachmentOutboxService->lastError());
    }
    const QString uploadPath = data["httpUploadPath"].toString();
    if (!uploadPath.isEmpty()) {
        if (NetworkManager::instance()->uploadRawFile(
                m_upload.uploadId, uploadPath, m_upload.filePath)) {
            m_upload.rawHttp = true;
            m_statusLabel->setText("正在通过 HTTP 上传...");
            return;
        }
        QMessageBox::warning(this, "上传失败", "无法启动 HTTP 上传，请重试");
        failActiveAttachment(QStringLiteral("HTTP_UPLOAD_START_FAILED"),
                             QStringLiteral("无法启动 HTTP 上传"));
        return;
    }
    // 旧服务端不返回 HTTP 地址时，保留 V1 Base64 分块兼容路径。
    m_upload.rawHttp = false;
    sendNextChunk();
}

void ChatWindow::sendNextChunk() {
    if (m_upload.uploadId.isEmpty()) {
        qWarning() << "[Upload] uploadId 为空，无法发送分块";
        QMessageBox::warning(this, "上传失败", "上传ID无效，请重试");
        return;
    }

    QFile file(m_upload.filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        QMessageBox::warning(this, "错误", "无法读取文件");
        failActiveAttachment(QStringLiteral("SOURCE_UNAVAILABLE"),
                             QStringLiteral("源文件不可读，请重新选择"));
        return;
    }

    file.seek(m_upload.offset);
    QByteArray chunk = file.read(m_upload.chunkSize);
    file.close();

    if (chunk.isEmpty()) return;

    QJsonObject data;
    data["uploadId"] = m_upload.uploadId;
    data["offset"]   = static_cast<double>(m_upload.offset);
    data["chunkData"] = QString::fromLatin1(chunk.toBase64());
    data["chunkSize"] = chunk.size();
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CHUNK, data));

    m_upload.offset += chunk.size();
    double progress = static_cast<double>(m_upload.offset) / m_upload.fileSize;
    m_statusLabel->setText(QString("上传中 %1%...").arg(static_cast<int>(progress * 60)));

    // 更新 UI 进度（如果有对应的消息）——上传阶段占 0-60%
    if (m_uploadingFileId != 0) {
        updateAllModelsDownloadProgress(m_uploadingFileId, Message::Uploading, progress * 0.6);
    }
}

void ChatWindow::onUploadChunkResponse(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "上传失败", data["error"].toString());
        failActiveAttachment(data["errorCode"].toString(
                                 QStringLiteral("UPLOAD_CHUNK_REJECTED")),
                             QStringLiteral("文件上传中断，可稍后重试"));
        return;
    }

    if (m_upload.offset >= m_upload.fileSize) {
        completeUploadBytes();
    } else if (m_uploadPaused) {
        // 上传暂停 — 不继续发送下一块
        m_statusLabel->setText(QString("上传已暂停 %1%")
            .arg(static_cast<int>(m_upload.offset * 60 / m_upload.fileSize)));
    } else {
        sendNextChunk();
    }
}

void ChatWindow::completeUploadBytes() {
    if (!m_attachmentOutboxService->recordFinalizing(
            m_username, m_upload.clientMessageId, m_upload.fileSize)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=finalizing outcome=degraded detail=%1")
            .arg(m_attachmentOutboxService->lastError());
    }
    QJsonObject endData;
    endData["uploadId"] = m_upload.uploadId;
    endData["clientMessageId"] = m_upload.clientMessageId;
    if (!m_upload.thumbnailData.isEmpty()) {
        endData["thumbnail"] = QString::fromLatin1(m_upload.thumbnailData.toBase64());
        m_upload.thumbnailData.clear();
    }
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_END, endData));
    m_statusLabel->setText("文件已上传，正在同步到云端...");
    const QString savedClientId = m_upload.clientMessageId;
    QTimer::singleShot(10000, this, [this, savedClientId]() {
        if (m_upload.clientMessageId == savedClientId) {
            failActiveAttachment(QStringLiteral("FINALIZE_TIMEOUT"),
                                 QStringLiteral("服务器确认超时，可安全重试"));
        }
    });
}

void ChatWindow::onUploadFinalizeResponse(const QJsonObject &data) {
    if (!data["uploadId"].toString().isEmpty() &&
        data["uploadId"].toString() != m_upload.uploadId)
        return;
    if (!data["clientMessageId"].toString().isEmpty() &&
        data["clientMessageId"].toString() != m_upload.clientMessageId)
        return;

    if (!data["success"].toBool()) {
        QMessageBox::warning(
            this, "上传失败",
            data["error"].toString("服务器未能确认文件消息"));
        failActiveAttachment(data["errorCode"].toString(
                                 QStringLiteral("UPLOAD_FINALIZE_REJECTED")),
                             QStringLiteral("文件发送失败，可稍后重试"));
        return;
    }
    const bool duplicate = data["duplicate"].toBool();
    const QString completedClientId = m_upload.clientMessageId;
    const QString completedFileName = QFileInfo(m_upload.filePath).fileName();
    const QString completedSourcePath = m_upload.filePath;
    if (!m_attachmentOutboxService->complete(m_username, completedClientId)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=complete outcome=degraded clientMessageId=%1 detail=%2")
            .arg(completedClientId, m_attachmentOutboxService->lastError());
    }
    clearUploadState(true);
    if (!duplicate && !completedSourcePath.isEmpty()) {
        m_pendingSentFiles[completedFileName] = completedSourcePath;
        m_pendingSentFilesByClientId[completedClientId] = completedSourcePath;
    }
    m_statusLabel->setText(duplicate ? "文件已在服务器中，已避免重复发送"
                                     : "文件发送成功");
    QTimer::singleShot(2000, this, [this]() {
        if (m_statusLabel->text().contains("文件")) m_statusLabel->clear();
    });
}

void ChatWindow::onRawUploadProgress(const QString &uploadId, qint64 sent, qint64 total) {
    if (!m_upload.rawHttp || uploadId != m_upload.uploadId || total <= 0) return;
    m_upload.offset = sent;
    const double ratio = qBound(0.0, static_cast<double>(sent) / total, 1.0);
    m_statusLabel->setText(QString("HTTP 上传中 %1%...").arg(static_cast<int>(ratio * 60)));
    if (m_uploadingFileId != 0)
        updateAllModelsDownloadProgress(m_uploadingFileId, Message::Uploading, ratio * 0.6);
}

void ChatWindow::onRawUploadFinished(const QString &uploadId, bool success,
                                     const QString &error) {
    if (!m_upload.rawHttp || uploadId != m_upload.uploadId) return;
    if (!success) {
        QMessageBox::warning(this, "上传失败", error);
        failActiveAttachment(QStringLiteral("HTTP_UPLOAD_FAILED"),
                             QStringLiteral("HTTP 上传中断，可稍后重试"));
        return;
    }
    m_upload.offset = m_upload.fileSize;
    completeUploadBytes();
}

void ChatWindow::onFileCosProgress(const QJsonObject &data) {
    const QString uploadId = data["uploadId"].toString();
    // 只处理当前上传任务
    if (uploadId.isEmpty() || uploadId != m_upload.uploadId)
        return;

    const qint64 sent  = static_cast<qint64>(data["sent"].toDouble());
    const qint64 total = static_cast<qint64>(data["total"].toDouble());

    if (total <= 0) return;

    // COS 阶段占 60%-100%
    double cosRatio = static_cast<double>(sent) / total;
    double overallProgress = 0.6 + cosRatio * 0.4;
    int pct = static_cast<int>(overallProgress * 100);

    m_statusLabel->setText(QString("同步到云端 %1%...").arg(pct));

    if (m_uploadingFileId != 0) {
        updateAllModelsDownloadProgress(m_uploadingFileId, Message::Uploading, overallProgress);
    }

    if (sent >= total) {
        // COS 上传完成
        m_statusLabel->setText("文件上传完成");
        m_upload.uploadId.clear();
        m_uploadingFileId = 0;
        m_uploadingFileName.clear();
        QTimer::singleShot(2000, this, [this]() {
            if (m_statusLabel->text() == "文件上传完成")
                m_statusLabel->clear();
        });
    }
}

void ChatWindow::pauseUpload() {
    if (m_upload.uploadId.isEmpty()) return;
    if (m_upload.rawHttp) {
        m_statusLabel->setText("HTTP 上传不支持暂停，可取消后重新上传");
        return;
    }
    m_uploadPaused = true;
    if (m_uploadingFileId != 0) {
        double progress = static_cast<double>(m_upload.offset) / m_upload.fileSize * 0.6;
        updateAllModelsDownloadProgress(m_uploadingFileId, Message::UploadPaused, progress);
    }
    m_statusLabel->setText(QString("上传已暂停 %1%")
        .arg(static_cast<int>(m_upload.offset * 60 / m_upload.fileSize)));
}

void ChatWindow::resumeUpload() {
    if (m_upload.uploadId.isEmpty()) return;
    if (m_upload.rawHttp) return;
    m_uploadPaused = false;
    if (m_uploadingFileId != 0) {
        double progress = static_cast<double>(m_upload.offset) / m_upload.fileSize * 0.6;
        updateAllModelsDownloadProgress(m_uploadingFileId, Message::Uploading, progress);
    }
    sendNextChunk();
}

void ChatWindow::cancelUpload() {
    if (m_upload.clientMessageId.isEmpty()) return;

    if (m_upload.rawHttp)
        NetworkManager::instance()->cancelRawUpload(m_upload.uploadId);

    // 通知服务器取消
    if (!m_upload.uploadId.isEmpty()) {
        QJsonObject data;
        data["uploadId"] = m_upload.uploadId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_CANCEL, data));
    }

    const QString cancelledClientId = m_upload.clientMessageId;
    if (!m_attachmentOutboxService->cancel(m_username, cancelledClientId)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=cancel outcome=degraded clientMessageId=%1 detail=%2")
            .arg(cancelledClientId, m_attachmentOutboxService->lastError());
    }
    clearUploadState(true);
    m_statusLabel->setText("上传已取消");
}

void ChatWindow::clearUploadState(bool removeTemporaryMessage) {
    // 清除本地上传状态 — 移除临时消息（房间模型和好友模型都要检查）
    if (removeTemporaryMessage && m_uploadingFileId != 0) {
        // 从所有模型中移除临时上传消息
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            it.value()->removeMessageByFileId(m_uploadingFileId);
        }
        for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
            it.value()->removeMessageByFileId(m_uploadingFileId);
        }
        // 清理临时缩略图
        QString tempThumb = FileCache::instance()->thumbDir()
                            + QString("/thumb_%1.jpg").arg(m_uploadingFileId);
        QFile::remove(tempThumb);
        QPixmapCache::remove(QString("vidthumb_%1").arg(m_uploadingFileId));
    }
    const QString clientMessageId = m_upload.clientMessageId;
    const QString fileName = QFileInfo(m_upload.filePath).fileName();
    if (!fileName.isEmpty() && m_pendingSentFiles.value(fileName) == m_upload.filePath)
        m_pendingSentFiles.remove(fileName);
    m_pendingSentFilesByClientId.remove(clientMessageId);
    m_upload.uploadId.clear();
    m_upload.clientMessageId.clear();
    m_upload.kind = LocalConversationRepository::Kind::Room;
    m_upload.conversationKey.clear();
    m_upload.roomId = 0;
    m_upload.peerUsername.clear();
    m_upload.contentType.clear();
    m_upload.filePath.clear();
    m_upload.fileSize = 0;
    m_upload.offset = 0;
    m_upload.thumbnailData.clear();
    m_upload.rawHttp = false;
    m_uploadPaused = false;
    m_uploadingFileId = 0;
    m_uploadingFileName.clear();
    QTimer::singleShot(0, this, [this] { processNextAttachment(); });
}

// ==================== 文件下载管理 ====================

void ChatWindow::pauseDownload(int fileId) {
    if (!m_downloads.contains(fileId)) return;
    double progress = static_cast<double>(m_downloads[fileId].offset) / m_downloads[fileId].fileSize;
    updateAllModelsDownloadProgress(fileId, Message::Paused, progress);
    // 对于活跃下载：标记为暂停，不再请求下一块（onDownloadChunkResponse 会检查）
    // 对于队列中的：只需标记状态即可
    m_statusLabel->setText("下载已暂停");
}

void ChatWindow::resumeDownload(int fileId) {
    if (!m_downloads.contains(fileId)) return;
    ChunkedDownload &dl = m_downloads[fileId];
    double progress = static_cast<double>(dl.offset) / dl.fileSize;
    updateAllModelsDownloadProgress(fileId, Message::Downloading, progress);

    if (m_activeDownloadId == fileId) {
        // 当前是活跃下载，继续请求下一块
        QJsonObject reqData;
        reqData["fileId"]   = fileId;
        reqData["offset"]   = static_cast<double>(dl.offset);
        reqData["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, reqData));
        m_statusLabel->setText(QString("下载中 %1%...").arg(static_cast<int>(progress * 100)));
    } else if (m_activeDownloadId == 0) {
        // 没有活跃下载，立即启动
        m_activeDownloadId = fileId;
        QJsonObject reqData;
        reqData["fileId"]   = fileId;
        reqData["offset"]   = static_cast<double>(dl.offset);
        reqData["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, reqData));
        m_statusLabel->setText(QString("下载中 %1%...").arg(static_cast<int>(progress * 100)));
    }
    // 否则仍在队列中等待
}

void ChatWindow::cancelDownload(int fileId) {
    if (m_httpDownloads.contains(fileId)) {
        NetworkManager::instance()->cancelRawDownload(fileId);
        m_httpDownloads.remove(fileId);
    }
    updateAllModelsDownloadProgress(fileId, Message::NotDownloaded, 0.0);
    m_downloads.remove(fileId);
    m_downloadQueue.removeAll(fileId);
    if (m_activeDownloadId == fileId) {
        m_activeDownloadId = 0;
        processNextDownload();
    }
    m_statusLabel->setText("下载已取消");
}

void ChatWindow::triggerFileDownload(int fileId, const QString &fileName, qint64 fileSize) {
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        int row = it.value()->findMessageByFileId(fileId);
        if (row >= 0) {
            QModelIndex idx = it.value()->index(row, 0);
            if (idx.data(MessageModel::FileClearedRole).toBool()
                && !FileCache::instance()->isCached(fileId)) {
                m_statusLabel->setText("文件已过期或被清除，无法下载");
                QMessageBox::information(this, QStringLiteral("提示"), QStringLiteral("文件已过期或被清除，无法下载"));
                return;
            }
            break;
        }
    }

    if (FileCache::instance()->isCached(fileId)) return;

    // 标记为下载中
    updateAllModelsDownloadProgress(fileId, Message::Downloading, 0.0);

    if (NetworkManager::instance()->downloadRawFile(fileId)) {
        m_httpDownloads[fileId] = qMakePair(fileName, fileSize);
        m_statusLabel->setText(QString("HTTP 下载中 %1...").arg(fileName));
        return;
    }

    if (fileSize > Protocol::MAX_SMALL_FILE) {
        // 大文件走分块下载
        startChunkedDownload(fileId, fileName, fileSize);
    } else {
        // 小文件直接请求
        QJsonObject reqData;
        reqData["fileId"]   = fileId;
        reqData["fileName"] = fileName;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_REQ, reqData));
        m_statusLabel->setText(QString("下载中 %1...").arg(fileName));
    }
}

void ChatWindow::processNextDownload() {
    if (m_downloadQueue.isEmpty()) return;
    int nextId = m_downloadQueue.takeFirst();
    if (!m_downloads.contains(nextId)) return;

    m_activeDownloadId = nextId;
    ChunkedDownload &dl = m_downloads[nextId];
    QJsonObject data;
    data["fileId"]   = nextId;
    data["offset"]   = 0.0;
    data["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, data));
    m_statusLabel->setText(QString("下载中 %1...").arg(dl.fileName));
}

void ChatWindow::updateAllModelsDownloadProgress(int fileId, int state, double progress) {
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        it.value()->updateDownloadProgress(fileId, state, progress);
    }
    for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
        it.value()->updateDownloadProgress(fileId, state, progress);
    }
    // 强制视图刷新
    m_messageView->viewport()->update();
}

void ChatWindow::onFileDownloadComplete(int fileId, const QString &fileName, const QByteArray &data) {
    // 缓存到本地
    QString localPath = FileCache::instance()->cacheFile(fileId, fileName, data);
    if (localPath.isEmpty()) {
        // 缓存失败，恢复为未下载状态以允许重试
        updateAllModelsDownloadProgress(fileId, Message::NotDownloaded, 0.0);
        m_statusLabel->setText(QString("文件缓存失败: %1").arg(fileName));
        return;
    }

    finishCachedDownload(fileId, fileName, localPath);
}

void ChatWindow::finishCachedDownload(int fileId, const QString &fileName,
                                      const QString &localPath) {
    m_statusLabel->setText(QString("文件已缓存: %1").arg(fileName));

    // 更新所有模型中该文件的下载状态
    updateAllModelsDownloadProgress(fileId, Message::Downloaded, 1.0);

    // 如果是图片，清除 QPixmapCache 并强制刷新视图
    static const QStringList imgExts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    QString suffix = QFileInfo(fileName).suffix().toLower();
    if (imgExts.contains(suffix)) {
        QPixmapCache::remove(QString("msgimg_%1").arg(fileId));
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            int row = it.value()->findMessageByFileId(fileId);
            if (row >= 0) {
                QModelIndex idx = it.value()->index(row, 0);
                emit it.value()->dataChanged(idx, idx);
            }
        }
        for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
            int row = it.value()->findMessageByFileId(fileId);
            if (row >= 0) {
                QModelIndex idx = it.value()->index(row, 0);
                emit it.value()->dataChanged(idx, idx);
            }
        }
        m_messageView->doItemsLayout();
        m_messageView->viewport()->update();
    }

    // 如果是视频，生成缩略图
    if (vidExts.contains(suffix)) {
        generateVideoThumbnail(fileId, localPath);
    }
}

void ChatWindow::onRawDownloadProgress(int fileId, qint64 received, qint64 total) {
    if (!m_httpDownloads.contains(fileId) || total <= 0) return;
    const double ratio = qBound(0.0, static_cast<double>(received) / total, 1.0);
    updateAllModelsDownloadProgress(fileId, Message::Downloading, ratio);
    m_statusLabel->setText(QString("HTTP 下载中 %1%...")
                               .arg(static_cast<int>(ratio * 100)));
}

void ChatWindow::onRawDownloadFinished(int fileId, bool success,
                                       const QString &temporaryPath,
                                       const QString &error) {
    if (!m_httpDownloads.contains(fileId)) {
        if (!temporaryPath.isEmpty()) QFile::remove(temporaryPath);
        return;
    }
    const auto request = m_httpDownloads.take(fileId);
    const QString fileName = request.first;
    const qint64 fileSize = request.second;
    if (success) {
        const QString localPath = FileCache::instance()->cacheFromLocal(
            fileId, fileName, temporaryPath);
        QFile::remove(temporaryPath);
        if (!localPath.isEmpty()) {
            finishCachedDownload(fileId, fileName, localPath);
            return;
        }
    }

    qWarning() << "[Download] HTTP 下载失败，回退 V1:" << error;
    if (fileSize > Protocol::MAX_SMALL_FILE) {
        startChunkedDownload(fileId, fileName, fileSize);
    } else {
        QJsonObject requestData;
        requestData["fileId"] = fileId;
        requestData["fileName"] = fileName;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_REQ, requestData));
    }
}

void ChatWindow::generateVideoThumbnail(int fileId, const QString &videoPath) {
    QByteArray jpegData = generateVideoThumbnailData(videoPath);
    if (jpegData.isEmpty()) return;

    // 保存缩略图
    QString tDir = FileCache::instance()->thumbDir();
    QString thumbPath = tDir + QString("/thumb_%1.jpg").arg(fileId);
    QFile f(thumbPath);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(jpegData);
        f.close();
        qInfo() << "[VideoThumb] 缩略图已保存:" << thumbPath;
    }

    // 刷新视图
    QPixmapCache::remove(QString("vidthumb_%1").arg(fileId));
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        int row = it.value()->findMessageByFileId(fileId);
        if (row >= 0) {
            QModelIndex idx = it.value()->index(row, 0);
            emit it.value()->dataChanged(idx, idx);
        }
    }
    m_messageView->viewport()->update();
}

QByteArray ChatWindow::generateVideoThumbnailData(const QString &videoPath) {
#ifdef Q_OS_WIN
    QByteArray result;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    bool needUninit = SUCCEEDED(hr);

    IShellItemImageFactory *factory = nullptr;
    hr = SHCreateItemFromParsingName(
        reinterpret_cast<LPCWSTR>(QDir::toNativeSeparators(videoPath).utf16()),
        nullptr, IID_PPV_ARGS(&factory));

    if (SUCCEEDED(hr) && factory) {
        HBITMAP hBitmap = nullptr;
        SIZE size = {480, 270};
        hr = factory->GetImage(size, SIIGBF_THUMBNAILONLY | SIIGBF_BIGGERSIZEOK, &hBitmap);

        if (SUCCEEDED(hr) && hBitmap) {
            BITMAP bm;
            GetObject(hBitmap, sizeof(BITMAP), &bm);

            BITMAPINFOHEADER bi = {};
            bi.biSize = sizeof(BITMAPINFOHEADER);
            bi.biWidth = bm.bmWidth;
            bi.biHeight = -bm.bmHeight;
            bi.biPlanes = 1;
            bi.biBitCount = 32;
            bi.biCompression = BI_RGB;

            QImage img(bm.bmWidth, bm.bmHeight, QImage::Format_ARGB32);
            HDC hdc = GetDC(nullptr);
            GetDIBits(hdc, hBitmap, 0, bm.bmHeight, img.bits(),
                     reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS);
            ReleaseDC(nullptr, hdc);
            DeleteObject(hBitmap);

            if (!img.isNull()) {
                QBuffer buf(&result);
                buf.open(QIODevice::WriteOnly);
                img.save(&buf, "JPEG", 85);
            }
        }
        factory->Release();
    }

    if (needUninit) CoUninitialize();
    return result;
#else
    Q_UNUSED(videoPath)
    return {};
#endif
}

void ChatWindow::startChunkedDownload(int fileId, const QString &fileName, qint64 fileSize) {
    ChunkedDownload dl;
    dl.fileId   = fileId;
    dl.fileName = fileName;
    dl.fileSize = fileSize;
    dl.offset   = 0;
    dl.buffer.clear();
    dl.buffer.reserve(static_cast<int>(qMin(fileSize, (qint64)100 * 1024 * 1024)));
    m_downloads[fileId] = dl;

    // 如果没有正在进行的分块下载，立即开始
    if (m_activeDownloadId == 0) {
        m_activeDownloadId = fileId;
        QJsonObject data;
        data["fileId"]   = fileId;
        data["offset"]   = 0.0;
        data["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, data));
        m_statusLabel->setText(QString("下载中 %1...").arg(fileName));
    } else {
        // 已有分块下载在进行，加入队列
        if (!m_downloadQueue.contains(fileId))
            m_downloadQueue.append(fileId);
    }
}

void ChatWindow::onDownloadChunkResponse(const QJsonObject &data) {
    int fileId = data["fileId"].toInt();

    if (!data["success"].toBool()) {
        m_statusLabel->setText("下载失败: " + data["error"].toString());
        updateAllModelsDownloadProgress(fileId, Message::NotDownloaded, 0.0);
        m_downloads.remove(fileId);
        if (m_activeDownloadId == fileId) {
            m_activeDownloadId = 0;
            processNextDownload();
        }
        return;
    }

    if (!m_downloads.contains(fileId)) return;
    ChunkedDownload &dl = m_downloads[fileId];

    QByteArray chunk = QByteArray::fromBase64(data["chunkData"].toString().toLatin1());
    dl.buffer.append(chunk);
    dl.offset += chunk.size();

    double progress = static_cast<double>(dl.offset) / dl.fileSize;

    // 检查是否在追加数据期间被暂停了
    // 用当前模型状态判断（pauseDownload 会设为 Paused）
    bool isPaused = false;
    for (auto it = m_models.begin(); it != m_models.end() && !isPaused; ++it) {
        int row = it.value()->findMessageByFileId(fileId);
        if (row >= 0) {
            const Message &msg = it.value()->messageAt(row);
            if (msg.downloadState() == Message::Paused) {
                isPaused = true;
            }
        }
    }
    for (auto it = m_friendModels.begin(); it != m_friendModels.end() && !isPaused; ++it) {
        int row = it.value()->findMessageByFileId(fileId);
        if (row >= 0) {
            const Message &msg = it.value()->messageAt(row);
            if (msg.downloadState() == Message::Paused) {
                isPaused = true;
            }
        }
    }

    if (isPaused) {
        // 暂停状态：数据已保存到 buffer，但不继续请求
        updateAllModelsDownloadProgress(fileId, Message::Paused, progress);
        return;
    }

    updateAllModelsDownloadProgress(fileId, Message::Downloading, progress);
    m_statusLabel->setText(QString("下载中 %1%...").arg(static_cast<int>(progress * 100)));

    if (dl.offset >= dl.fileSize) {
        // 下载完毕
        onFileDownloadComplete(fileId, dl.fileName, dl.buffer);
        m_downloads.remove(fileId);
        if (m_activeDownloadId == fileId) {
            m_activeDownloadId = 0;
            processNextDownload();
        }
    } else {
        // 继续请求下一个块
        QJsonObject reqData;
        reqData["fileId"]   = fileId;
        reqData["offset"]   = static_cast<double>(dl.offset);
        reqData["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, reqData));
    }
}

// ==================== 消息撤回 ====================

void ChatWindow::onRecallMessage() {
    QModelIndex idx = m_messageView->currentIndex();
    if (!idx.isValid()) return;

    // 好友私聊撤回
    if (m_isFriendChat) {
        if (m_currentFriendUsername.isEmpty()) return;
        MessageModel *model = getOrCreateFriendModel(m_currentFriendUsername);
        const Message &msg = model->messageAt(idx.row());
        QJsonObject data;
        data["messageId"] = msg.id();
        data["friendUsername"] = m_currentFriendUsername;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FRIEND_RECALL_REQ, data));
        return;
    }

    // 房间撤回
    if (m_currentRoomId < 0) return;
    MessageModel *model = getOrCreateModel(m_currentRoomId);
    const Message &msg = model->messageAt(idx.row());
    NetworkManager::instance()->sendMessage(
        Protocol::makeRecallReq(msg.id(), m_currentRoomId));
}

void ChatWindow::onRecallResponse(bool success, int messageId, const QString &error) {
    if (!success) {
        QMessageBox::warning(this, "撤回失败", error);
        return;
    }
    Q_UNUSED(messageId)
}

void ChatWindow::onRecallNotify(const QJsonObject &data) {
    const int messageId = data["messageId"].toInt();
    const int roomId = data["roomId"].toInt();
    MessageModel *model = getOrCreateModel(roomId);

    // 清除该消息的文件缓存
    int row = model->findMessageRow(messageId);
    if (row >= 0) {
        const Message &msg = model->messageAt(row);
        if (msg.contentType() == Message::File && msg.fileId() != 0) {
            FileCache::instance()->removeFile(msg.fileId());
            QPixmapCache::remove(QString("msgimg_%1").arg(msg.fileId()));
            // 删除视频缩略图
            QPixmapCache::remove(QString("vidthumb_%1").arg(msg.fileId()));
            QString thumbPath = FileCache::instance()->thumbDir() + QString("/thumb_%1.jpg").arg(msg.fileId());
            QFile::remove(thumbPath);
        }
    }

    model->recallMessage(messageId);
    advanceRoomSyncCursor(roomId, syncSequenceFrom(data));
    persistRoomSnapshot(roomId);
}

// ==================== 管理员功能 ====================

void ChatWindow::onAdminStatusChanged(int roomId, bool isAdmin) {
    m_adminRooms[roomId] = isAdmin;
    if (roomId == m_currentRoomId) {
        refreshConversationShellText();
        if (isAdmin) {
            m_statusLabel->setText(QStringLiteral("提示: 右键消息或用户列表可使用管理功能"));
        }
        // 刷新用户列表以实时更新管理员名字颜色
        QJsonObject userData;
        userData["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::USER_LIST_REQ, userData));
    }
}

void ChatWindow::onSetAdminResponse(bool success, int roomId, const QString &username, const QString &error) {
    Q_UNUSED(roomId)
    if (success) {
        m_statusLabel->setText(QStringLiteral("已设置 %1 的管理员状态").arg(username));
        // 刷新用户列表以更新管理员标识
        if (roomId == m_currentRoomId) {
            QJsonObject userData;
            userData["roomId"] = roomId;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::USER_LIST_REQ, userData));
        }
    } else {
        QMessageBox::warning(this, "设置管理员失败", error);
    }
}

void ChatWindow::onDeleteMsgsResponse(const QJsonObject &data) {
    const bool success = data["success"].toBool();
    const int roomId = data["roomId"].toInt();
    const QJsonArray deletedFileIds = data["deletedFileIds"].toArray();
    if (success) {
        m_statusLabel->setText(QStringLiteral("已删除 %1 条消息").arg(data["deletedCount"].toInt()));
        // 只清除服务端返回的被删除文件的缓存
        for (const QJsonValue &v : deletedFileIds) {
            int fid = v.toInt();
            FileCache::instance()->removeFile(fid);
            QPixmapCache::remove(QString("msgimg_%1").arg(fid));
            QPixmapCache::remove(QString("vidthumb_%1").arg(fid));
            QString thumbPath = FileCache::instance()->thumbDir() + QString("/thumb_%1.jpg").arg(fid);
            QFile::remove(thumbPath);
        }
        getOrCreateModel(roomId)->applyDeletionEvents({data});
        advanceRoomSyncCursor(roomId, syncSequenceFrom(data));
        persistRoomSnapshot(roomId);
    } else {
        QMessageBox::warning(this, "删除消息失败", data["error"].toString());
    }
}

void ChatWindow::onDeleteMsgsNotify(const QJsonObject &data) {
    const int roomId = data["roomId"].toInt();
    const QJsonArray deletedFileIds = data["deletedFileIds"].toArray();
    MessageModel *model = getOrCreateModel(roomId);

    // 只清除服务端返回的被删除文件的缓存
    for (const QJsonValue &v : deletedFileIds) {
        int fid = v.toInt();
        FileCache::instance()->removeFile(fid);
        QPixmapCache::remove(QString("msgimg_%1").arg(fid));
        QPixmapCache::remove(QString("vidthumb_%1").arg(fid));
        QString thumbPath = FileCache::instance()->thumbDir() + QString("/thumb_%1.jpg").arg(fid);
        QFile::remove(thumbPath);
    }

    model->applyDeletionEvents({data});
    advanceRoomSyncCursor(roomId, syncSequenceFrom(data));
    persistRoomSnapshot(roomId);

    m_statusLabel->setText("管理员清理了消息记录");
}

void ChatWindow::onUserContextMenu(const QPoint &pos) {
    if (m_currentRoomId < 0) return;

    QListWidgetItem *item = m_userList->itemAt(pos);
    if (!item) return;

    QString targetUser = item->data(Qt::UserRole).toString();
    QString targetDisplayName = item->data(Qt::UserRole + 3).toString();
    if (targetDisplayName.isEmpty()) targetDisplayName = targetUser;
    QMenu menu(this);

    // 右键自己的名字
    if (targetUser == m_username) {
        // 管理员可以放弃自己的管理员权限
        if (m_adminRooms.value(m_currentRoomId, false)) {
            menu.addAction(QStringLiteral("放弃管理员权限"), [this] {
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["username"] = m_username;
                data["isAdmin"] = false;
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_REQ, data));
            });
            menu.addSeparator();
        }
        menu.addAction(QStringLiteral("退出聊天室"), [this] {
            leaveRoom(m_currentRoomId);
        });
        menu.exec(m_userList->viewport()->mapToGlobal(pos));
        return;
    }

    // 管理员可以对其他用户操作
    if (m_adminRooms.value(m_currentRoomId, false)) {
        bool targetIsAdmin = false;
        QListWidgetItem *targetItem = findUserListItem(targetUser);
        if (targetItem)
            targetIsAdmin = targetItem->data(Qt::UserRole + 1).toBool();

        if (!targetIsAdmin) {
            menu.addAction(QStringLiteral("设为管理员"), [this, targetUser] {
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["username"] = targetUser;
                data["isAdmin"] = true;
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::SET_ADMIN_REQ, data));
            });

            menu.addAction(QStringLiteral("踢出聊天室"), [this, targetUser, targetDisplayName] {
                if (QMessageBox::question(this, "确认",
                        QString("确定要将 %1 踢出聊天室吗？").arg(targetDisplayName))
                    != QMessageBox::Yes) return;
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["username"] = targetUser;
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::KICK_USER_REQ, data));
            });
        }
    }

    if (!menu.isEmpty())
        menu.exec(m_userList->viewport()->mapToGlobal(pos));
}

void ChatWindow::onRoomContextMenu(const QPoint &pos) {
    QListWidgetItem *item = m_roomList->itemAt(pos);
    if (!item) return;

    int roomId = item->data(Qt::UserRole).toInt();

    QMenu menu(this);

    menu.addAction(QStringLiteral("房间设置"), [this, roomId] {
        showRoomSettingsDialog(roomId);
    });

    if (m_adminRooms.value(roomId, false)) {
        menu.addAction(WindowsLocaleCatalog::messages(
                           m_windowsLocaleViewModel->locale()).roomFileManagerTitle,
                       [this, roomId] {
            showRoomFileManagerDialog(roomId);
        });
    }

    menu.exec(m_roomList->viewport()->mapToGlobal(pos));
}

// ==================== 表情 ====================

void ChatWindow::onShowEmojiPicker() {
    QPoint pos = m_emojiBtn->mapToGlobal(QPoint(0, -m_emojiPicker->sizeHint().height()));
    m_emojiPicker->move(pos);
    m_emojiPicker->setVisible(!m_emojiPicker->isVisible());
}

void ChatWindow::onEmojiSelected(const QString &emoji) {
    m_inputEdit->insertPlainText(emoji);
    m_emojiPicker->hide();
}

// ==================== 右键菜单 ====================

void ChatWindow::onMessageContextMenu(const QPoint &pos) {
    QModelIndex idx = m_messageView->indexAt(pos);

    MessageModel *model = qobject_cast<MessageModel*>(m_messageView->model());
    if (!model) return;

    // 检查点击区域（气泡 / 头像 / 空白）
    bool clickedOnBubble = false;
    bool clickedOnAvatar = false;
    if (idx.isValid()) {
        QStyleOptionViewItem opt;
        opt.rect = m_messageView->visualRect(idx);
        opt.font = m_messageView->font();
        QRect bubble = m_delegate->bubbleRectForIndex(opt, idx);
        QRect avatar = m_delegate->avatarRectForIndex(opt, idx);
        clickedOnBubble = bubble.contains(pos);
        clickedOnAvatar = avatar.contains(pos);
    }

    // 右键头像 → 查看用户信息（自己和他人均可）
    if (clickedOnAvatar && idx.isValid()) {
        QString sender = idx.data(MessageModel::SenderRole).toString();
        QString senderName = idx.data(MessageModel::SenderNameRole).toString();
        QMenu menu(this);
        menu.addAction(QStringLiteral("查看用户信息"), [this, sender, senderName] {
            showUserInfoDialog(sender, senderName);
        });
        menu.exec(m_messageView->viewport()->mapToGlobal(pos));
        return;
    }

    QMenu menu(this);
    bool hasMessageActions = false;

    // ====== 消息相关操作（需要点击在气泡上）======
    if (idx.isValid() && clickedOnBubble) {
        const Message &msg = model->messageAt(idx.row());
        int dlState = idx.data(MessageModel::DownloadStateRole).toInt();
        bool isUploading = (dlState == Message::Uploading || dlState == Message::UploadPaused);

        if (msg.isMine() && msg.deliveryState() == Message::Failed
            && !msg.clientMessageId().isEmpty()
            && (msg.contentType() == Message::Text || msg.contentType() == Message::Emoji)) {
            const Message retry = msg;
            menu.addAction(QStringLiteral("重试发送"), [this, model, retry] {
                OutgoingMessageService::Command command;
                if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
                    const auto target = OutgoingMessageService::directTarget(
                        friendConversationKey(m_currentFriendUsername),
                        m_currentFriendUsername);
                    if (!m_outgoingMessageService->prepareRetry(
                            m_username, target, retry,
                            m_conversationSyncService->cursor(
                                friendConversation(m_currentFriendUsername)),
                            &command)) return;
                } else if (m_currentRoomId > 0) {
                    const auto target = OutgoingMessageService::roomTarget(m_currentRoomId);
                    if (!m_outgoingMessageService->prepareRetry(
                            m_username, target, retry,
                            m_conversationSyncService->cursor(
                                roomConversation(m_currentRoomId)),
                            &command)) return;
                } else return;
                if (!m_outgoingMessageService->lastError().isEmpty()) {
                    qWarning().noquote() << QStringLiteral(
                        "[Outbox] operation=manual-retry outcome=degraded detail=%1")
                        .arg(m_outgoingMessageService->lastError());
                }
                model->updateDeliveryState(retry.clientMessageId(), Message::Sending);
                dispatchOutgoing(command);
            });
            hasMessageActions = true;
        }

        // 文件消息操作
        if (msg.contentType() == Message::File) {
            int fileId = msg.fileId();

            // 上传中/上传暂停 → 提供暂停/恢复/取消上传
            if (isUploading) {
                if (dlState == Message::Uploading) {
                    menu.addAction("暂停上传", [this] { pauseUpload(); });
                } else {
                    menu.addAction("恢复上传", [this] { resumeUpload(); });
                }
                menu.addAction("取消上传", [this] { cancelUpload(); });
                hasMessageActions = true;
            }
            // 下载中/下载暂停 → 提供取消下载
            else if (dlState == Message::Downloading || dlState == Message::Paused) {
                if (dlState == Message::Downloading) {
                    menu.addAction("暂停下载", [this, fileId] { pauseDownload(fileId); });
                } else {
                    menu.addAction("恢复下载", [this, fileId] { resumeDownload(fileId); });
                }
                menu.addAction("取消下载", [this, fileId] { cancelDownload(fileId); });
                hasMessageActions = true;
            }
            // 已缓存 → 打开文件
            else if (FileCache::instance()->isCached(fileId)) {
                menu.addAction("打开文件", [fileId] {
                    QString path = FileCache::instance()->cachedFilePath(fileId);
                    FileCache::openWithSystem(path);
                });
                menu.addAction("打开所在文件夹", [fileId] {
                    QString path = FileCache::instance()->cachedFilePath(fileId);
                    QFileInfo fi(path);
                    FileCache::openWithSystem(fi.absolutePath());
                });
                hasMessageActions = true;
            }
            // 未下载
            else if (fileId != 0) {
                menu.addAction("下载文件", [this, &msg] {
                    triggerFileDownload(msg.fileId(), msg.fileName(), msg.fileSize());
                });
                hasMessageActions = true;
            }
        }

        // 撤回：仅在非上传状态下允许
        if (msg.sender() == m_username && !msg.recalled() && !isUploading) {
            int secs = msg.timestamp().secsTo(QDateTime::currentDateTime());
            if (secs <= Protocol::RECALL_TIME_LIMIT_SEC) {
                menu.addAction("撤回消息", this, &ChatWindow::onRecallMessage);
                hasMessageActions = true;
            }
        }

        // 复制文本
        menu.addAction("复制文本", [&msg] {
            QApplication::clipboard()->setText(msg.content());
        });
        hasMessageActions = true;

        // 转发消息/文件
        if (!isUploading && msg.contentType() != Message::System) {
            menu.addAction("转发消息", [this, msg] {
                QList<ForwardSelectDialog::RoomTarget> roomTargets;
                for (int i = 0; i < m_roomList->count(); ++i) {
                    auto *it = m_roomList->item(i);
                    int rid = it->data(Qt::UserRole).toInt();
                    if (rid == m_currentRoomId) continue;
                    ForwardSelectDialog::RoomTarget target;
                    target.roomId = rid;
                    target.roomName = it->text();
                    target.unread = m_roomUnread.value(rid, 0);
                    roomTargets.append(target);
                }

                QList<ForwardSelectDialog::FriendTarget> friendTargets;
                for (const QJsonValue &v : m_friendData) {
                    QJsonObject f = v.toObject();
                    QString uname = f["username"].toString();
                    if (uname.isEmpty() || uname == m_currentFriendUsername) continue;
                    ForwardSelectDialog::FriendTarget target;
                    target.username = uname;
                    target.displayName = f["displayName"].toString();
                    target.isOnline = f["online"].toBool(false);
                    target.unread = m_friendUnread.value(uname, 0);
                    friendTargets.append(target);
                }

                ForwardSelectDialog dlg(
                    roomTargets, friendTargets, this, m_windowsLocaleViewModel);
                if (dlg.exec() != QDialog::Accepted) return;

                QSet<int> targetRooms = dlg.selectedRoomIds();
                QSet<QString> targetFriends = dlg.selectedFriendUsernames();

                if (targetRooms.isEmpty() && targetFriends.isEmpty()) {
                    QMessageBox::information(this, QStringLiteral("转发"), QStringLiteral("没有有效目标，已取消转发"));
                    return;
                }

                int forwardedCount = 0;

                if (msg.contentType() == Message::File || msg.contentType() == Message::Image || msg.contentType() == Message::Video) {
                    const int fileId = msg.fileId();
                    if (fileId == 0 || msg.fileCleared()) {
                        QMessageBox::warning(this, QStringLiteral("转发失败"),
                                             QStringLiteral("文件已过期或缺少服务端标识，无法转发"));
                        return;
                    }
                    if (targetRooms.size() + targetFriends.size() > Protocol::MAX_FILE_FORWARD_TARGETS) {
                        QMessageBox::warning(this, QStringLiteral("转发失败"),
                                             QStringLiteral("一次最多转发到 %1 个会话")
                                                 .arg(Protocol::MAX_FILE_FORWARD_TARGETS));
                        return;
                    }
                    if (!NetworkManager::instance()->supportsServerFileForward()) {
                        const int legacyCount = forwardFileWithLegacyProtocol(
                            fileId, msg.fileName(), targetRooms, targetFriends);
                        if (legacyCount > 0) {
                            QMessageBox::information(
                                this, QStringLiteral("转发完成"),
                                QStringLiteral("已通过兼容协议转发到 %1 个会话")
                                    .arg(legacyCount));
                        }
                        return;
                    }
                    QJsonArray roomIds;
                    for (int rid : targetRooms) {
                        roomIds.append(rid);
                    }
                    QJsonArray friendUsernames;
                    for (const QString &uname : targetFriends) {
                        friendUsernames.append(uname);
                    }
                    QJsonObject data;
                    data["sourceFileId"] = fileId;
                    data["roomIds"] = roomIds;
                    data["friendUsernames"] = friendUsernames;
                    NetworkManager::instance()->sendMessage(
                        Protocol::makeMessage(Protocol::MsgType::FILE_FORWARD_REQ, data));
                    m_statusLabel->setText("正在服务端转发文件...");
                    return;
                } else {
                    QString ct = (msg.contentType() == Message::Emoji) ? QStringLiteral("emoji") : QStringLiteral("text");

                    for (int rid : targetRooms) {
                        NetworkManager::instance()->sendMessage(
                            Protocol::makeChatMsg(rid, m_username, msg.content(), ct));
                        ++forwardedCount;
                    }

                    for (const QString &uname : targetFriends) {
                        NetworkManager::instance()->sendMessage(
                            Protocol::makeFriendChatMsg(uname, msg.content(), ct));
                        ++forwardedCount;
                    }
                }

                QMessageBox::information(this, QStringLiteral("转发完成"),
                    QStringLiteral("已转发到 %1 个会话").arg(forwardedCount));
            });
            hasMessageActions = true;
        }

        // 管理员：删除此消息（仅在非上传状态下）
        if (m_adminRooms.value(m_currentRoomId, false) && !msg.recalled() && !isUploading) {
            menu.addSeparator();
            int msgId = msg.id();
            menu.addAction("删除此消息", [this, msgId] {
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["mode"] = QStringLiteral("selected");
                data["clientOperationId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
                QJsonArray ids;
                ids.append(msgId);
                data["messageIds"] = ids;
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
            });
        }
    }

    // ====== 管理员批量操作（始终显示，无需选中消息）======
    if (m_adminRooms.value(m_currentRoomId, false)) {
        if (hasMessageActions) menu.addSeparator();
        QMenu *adminMenu = menu.addMenu("管理员操作");

        adminMenu->addAction("清空所有消息", [this] {
            if (QMessageBox::question(this, "确认", "确定要清空所有聊天记录吗？\n此操作不可恢复！")
                == QMessageBox::Yes) {
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["mode"] = QStringLiteral("all");
                data["clientOperationId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
            }
        });

        adminMenu->addAction("删除N天前的消息...", [this] {
            bool ok;
            int days = QInputDialog::getInt(this, "删除旧消息",
                "删除多少天前的消息:", 7, 1, 365, 1, &ok);
            if (!ok) return;
            QDateTime cutoff = QDateTime::currentDateTime().addDays(-days);
            QJsonObject data;
            data["roomId"] = m_currentRoomId;
            data["mode"] = QStringLiteral("before");
            data["clientOperationId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
            data["timestamp"] = static_cast<double>(cutoff.toMSecsSinceEpoch());
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
        });

        adminMenu->addAction("删除最近N天的消息...", [this] {
            bool ok;
            int days = QInputDialog::getInt(this, "删除近期消息",
                "删除最近几天的消息:", 1, 1, 365, 1, &ok);
            if (!ok) return;
            QDateTime cutoff = QDateTime::currentDateTime().addDays(-days);
            QJsonObject data;
            data["roomId"] = m_currentRoomId;
            data["mode"] = QStringLiteral("after");
            data["clientOperationId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
            data["timestamp"] = static_cast<double>(cutoff.toMSecsSinceEpoch());
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
        });
    }

    if (!menu.isEmpty()) {
        menu.exec(m_messageView->viewport()->mapToGlobal(pos));
    }
}

int ChatWindow::forwardFileWithLegacyProtocol(
    int fileId, const QString &fileName, const QSet<int> &roomIds,
    const QSet<QString> &friendUsernames) {
    if (!FileCache::instance()->isCached(fileId)) {
        QMessageBox::warning(this, QStringLiteral("转发失败"),
                             QStringLiteral("旧服务端需要先下载文件后才能转发"));
        return 0;
    }
    QFile file(FileCache::instance()->cachedFilePath(fileId));
    if (!file.open(QIODevice::ReadOnly)) {
        QMessageBox::warning(this, QStringLiteral("转发失败"),
                             QStringLiteral("无法读取本地缓存文件"));
        return 0;
    }
    const QByteArray bytes = file.readAll();
    if (bytes.isEmpty() || bytes.size() > Protocol::MAX_SMALL_FILE) {
        QMessageBox::warning(this, QStringLiteral("转发失败"),
                             QStringLiteral("旧服务端仅支持转发 8MB 以内的文件"));
        return 0;
    }
    const QString encoded = QString::fromLatin1(bytes.toBase64());
    int count = 0;
    for (int roomId : roomIds) {
        QJsonObject data;
        data["roomId"] = roomId;
        data["fileName"] = fileName;
        data["fileSize"] = bytes.size();
        data["fileData"] = encoded;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_SEND, data));
        ++count;
    }
    for (const QString &friendUsername : friendUsernames) {
        QJsonObject data;
        data["friendUsername"] = friendUsername;
        data["fileName"] = fileName;
        data["fileSize"] = bytes.size();
        data["fileData"] = encoded;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FRIEND_FILE_SEND, data));
        ++count;
    }
    return count;
}

void ChatWindow::onFileForwardResponse(const QJsonObject &data) {
    const int forwarded = data["forwardedCount"].toInt();
    const int failed = data["failedCount"].toInt();
    m_statusLabel->clear();
    if (forwarded == 0) {
        QMessageBox::warning(this, QStringLiteral("转发失败"),
                             data["error"].toString(QStringLiteral("服务端未完成任何转发")));
        return;
    }
    if (failed > 0) {
        QMessageBox::warning(this, QStringLiteral("部分转发完成"),
                             QStringLiteral("已转发到 %1 个会话，%2 个目标失败")
                                 .arg(forwarded).arg(failed));
        return;
    }
    QMessageBox::information(this, QStringLiteral("转发完成"),
                             QStringLiteral("已转发到 %1 个会话").arg(forwarded));
}

// ==================== 主题 ====================

void ChatWindow::onToggleTheme() {
    ThemeManager::instance()->toggleTheme();
    ThemeManager::instance()->applyTheme(qApp);
}

// ==================== 连接状态 ====================

void ChatWindow::onConnected() {
    m_connectionStatusViewModel->setConnected();

    // 重连后请求房间列表，onRoomListReceived 会自动加入合适的房间
    // 不再额外发送 JOIN_ROOM_REQ，避免重复加入
    requestRoomList();
    requestCurrentRoomResume();
    requestCurrentFriendResume();
}

void ChatWindow::onDisconnected() {
    if (!m_upload.clientMessageId.isEmpty()) {
        if (m_upload.rawHttp && !m_upload.uploadId.isEmpty())
            NetworkManager::instance()->cancelRawUpload(m_upload.uploadId);
        m_attachmentOutboxService->recordPendingAuthorization(
            m_username, m_upload.clientMessageId);
        clearUploadState(true);
    }
    m_attachmentQueue.clear();
    m_queuedAttachmentIds.clear();
    m_connectionStatusViewModel->setDisconnected();
}

void ChatWindow::onReconnecting(int attempt) {
    m_connectionStatusViewModel->setReconnecting(attempt);
}

// ==================== 窗口事件 ====================

void ChatWindow::closeEvent(QCloseEvent *event) {
    flushCurrentDraft();
    if (m_forceQuit) {
        // 菜单退出：断开网络并彻底退出（含系统托盘）
        NetworkManager::instance()->disconnectFromServer();
        event->accept();
        qApp->quit();
    } else if (m_trayManager && m_trayManager->isAvailable()) {
        // 点击 X：最小化到托盘
        hide();
        const auto &copy = WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale());
        m_trayManager->showNotification(
            copy.mainTrayMinimizedTitle, copy.mainTrayMinimizedBody);
        event->ignore();
    } else {
        NetworkManager::instance()->disconnectFromServer();
        event->accept();
    }
}

void ChatWindow::moveEvent(QMoveEvent *event) {
    QMainWindow::moveEvent(event);
}

void ChatWindow::resizeEvent(QResizeEvent *event) {
    QMainWindow::resizeEvent(event);
}

bool ChatWindow::nativeEvent(const QByteArray &eventType, void *message, qintptr *result) {
    return QMainWindow::nativeEvent(eventType, message, result);
}

// ==================== 在线状态更新 ====================

void ChatWindow::onUserOnline(int roomId, const QString &username, const QString &displayName) {
    Q_UNUSED(displayName)
    if (roomId == m_currentRoomId) {
        QListWidgetItem *item = findUserListItem(username);
        if (item) {
            item->setData(Qt::UserRole + 2, true);
            updateUserListItemWidget(item);
        }
    }
}

void ChatWindow::onUserOffline(int roomId, const QString &username, const QString &displayName) {
    Q_UNUSED(displayName)
    if (roomId == m_currentRoomId) {
        QListWidgetItem *item = findUserListItem(username);
        if (item) {
            item->setData(Qt::UserRole + 2, false);
            updateUserListItemWidget(item);
        }
    }
}

// ==================== 退出聊天室 ====================

void ChatWindow::leaveRoom(int roomId) {
    // 获取房间名称
    QString roomName;
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            roomName = m_roomList->item(i)->text();
            break;
        }
    }

    if (QMessageBox::question(this, "退出聊天室",
            QString("确定要退出聊天室 %1 吗？").arg(roomName))
        != QMessageBox::Yes) return;

    NetworkManager::instance()->sendMessage(Protocol::makeLeaveRoom(roomId));
}

void ChatWindow::onLeaveRoomResponse(bool success, int roomId) {
    if (success) {
        removeCachedRoom(roomId);
        // 从房间列表中移除
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                delete m_roomList->takeItem(i);
                break;
            }
        }
        // 清理数据
        if (m_currentRoomId == roomId)
            m_messageView->setModel(nullptr);
        if (m_models.contains(roomId)) {
            delete m_models.take(roomId);
        }
        m_adminRooms.remove(roomId);
        m_joinedRooms.remove(roomId);
        m_roomMaxFileSize.remove(roomId);
        m_roomTotalFileSpace.remove(roomId);
        m_roomMaxFileCount.remove(roomId);
        m_roomMaxMembers.remove(roomId);

        // 切换到另一个房间
        if (m_currentRoomId == roomId) {
            m_currentRoomId = -1;
            m_messageView->setModel(nullptr);
            m_restoringDraft = true;
            m_inputEdit->clear();
            m_restoringDraft = false;
            if (m_roomList->count() > 0) {
                m_roomList->setCurrentRow(0);
                onRoomSelected(m_roomList->item(0));
            } else {
                m_currentRoomId = -1;
                refreshConversationShellText();
                m_userList->clear();
                m_messageView->setModel(nullptr);
            }
        }
    }
}

// ==================== 用户列表辅助方法 ====================

void ChatWindow::addUserListItem(const QString &username, const QString &displayName, bool isAdmin, bool isOnline) {
    auto *item = new QListWidgetItem;
    item->setData(Qt::UserRole, username);     // uniqueId
    item->setData(Qt::UserRole + 1, isAdmin);
    item->setData(Qt::UserRole + 2, isOnline);
    item->setData(Qt::UserRole + 3, displayName); // 显示用昵称
    item->setSizeHint(QSize(0, 40));

    m_userList->addItem(item);
    updateUserListItemWidget(item);

    // 请求未缓存用户的头像
    if (!s_avatarCache.contains(username)) {
        requestAvatar(username);
    }
}

void ChatWindow::updateUserListItemWidget(QListWidgetItem *item) {
    QString username = item->data(Qt::UserRole).toString();
    QString displayName = item->data(Qt::UserRole + 3).toString();
    if (displayName.isEmpty()) displayName = username;
    bool isAdmin = item->data(Qt::UserRole + 1).toBool();
    bool isOnline = item->data(Qt::UserRole + 2).toBool();

    auto *widget = new QWidget;
    auto *layout = new QHBoxLayout(widget);
    layout->setContentsMargins(4, 4, 4, 4);
    layout->setSpacing(4);

    auto *nameLabel = new QLabel(displayName);
    if (isAdmin) {
        nameLabel->setStyleSheet("color: #C5A200;");
    }

    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    auto *statusLabel = new QLabel(isOnline
        ? copy.mainConversationMemberOnline
        : copy.mainConversationMemberOffline);
    if (isOnline) {
        statusLabel->setStyleSheet("color: green; font-size: 11px;");
    } else {
        statusLabel->setStyleSheet("color: gray; font-size: 11px;");
    }
    statusLabel->setAlignment(Qt::AlignRight | Qt::AlignVCenter);

    layout->addWidget(nameLabel);
    layout->addStretch();
    layout->addWidget(statusLabel);

    item->setData(Qt::AccessibleTextRole, isOnline
        ? copy.mainConversationMemberOnlineAccessible.arg(displayName)
        : copy.mainConversationMemberOfflineAccessible.arg(displayName));
    m_userList->setItemWidget(item, widget);
}

QListWidgetItem* ChatWindow::findUserListItem(const QString &username) {
    for (int i = 0; i < m_userList->count(); ++i) {
        if (m_userList->item(i)->data(Qt::UserRole).toString() == username)
            return m_userList->item(i);
    }
    return nullptr;
}

// ==================== 头像功能 ====================

void ChatWindow::onChangeAvatar() {
    QString filePath = QFileDialog::getOpenFileName(this, "选择头像图片", QString(),
        "图片文件 (*.png *.jpg *.jpeg *.bmp *.gif)");
    if (filePath.isEmpty()) return;

    QPixmap img(filePath);
    if (img.isNull()) {
        QMessageBox::warning(this, "错误", "无法加载图片");
        return;
    }

    AvatarCropDialog dlg(img, this, m_windowsLocaleViewModel);
    if (dlg.exec() != QDialog::Accepted) return;

    QPixmap cropped = dlg.croppedAvatar();
    if (cropped.isNull()) return;

    // 转为 PNG 字节数据
    QByteArray pngData;
    QBuffer buf(&pngData);
    buf.open(QIODevice::WriteOnly);
    cropped.save(&buf, "PNG");
    buf.close();

    if (pngData.size() > 256 * 1024) {
        QMessageBox::warning(this, "提示", "头像数据过大，请选择更小的图片或裁剪区域");
        return;
    }

    // 发送上传请求
    QJsonObject data;
    data["avatarData"] = QString::fromLatin1(pngData.toBase64());
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::AVATAR_UPLOAD_REQ, data));
}

void ChatWindow::onAvatarUploadResponse(bool success, const QString &error) {
    if (success) {
        m_statusLabel->setText("头像上传成功");
        // 请求自己的头像以更新缓存
        requestAvatar(m_username, true);
    } else {
        QMessageBox::warning(this, "头像上传失败", error);
    }
}

void ChatWindow::onAvatarGetResponse(const QString &username, const QByteArray &avatarData) {
    if (avatarData.isEmpty()) return;
    cacheAvatar(username, avatarData);
}

void ChatWindow::onAvatarUpdateNotify(const QString &username, const QByteArray &avatarData) {
    if (avatarData.isEmpty()) return;
    cacheAvatar(username, avatarData);
}

void ChatWindow::cacheAvatar(const QString &username, const QByteArray &data) {
    QPixmap px;
    px.loadFromData(data);
    if (px.isNull()) return;

    s_avatarCache[username] = px;

    // 如果是自己的头像，更新预览
    if (username == m_username && m_avatarPreview) {
        QPixmap scaled = px.scaled(48, 48, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        // 裁剪为圆形
        QPixmap circle(48, 48);
        circle.fill(Qt::transparent);
        QPainter painter(&circle);
        painter.setRenderHint(QPainter::Antialiasing);
        QPainterPath path;
        path.addEllipse(0, 0, 48, 48);
        painter.setClipPath(path);
        painter.drawPixmap(0, 0, scaled);
        painter.end();
        m_avatarPreview->setPixmap(circle);
        if (m_profileDialog) m_profileDialog->updateAvatar(px);
    }

    // 刷新消息列表以显示新头像
    if (m_messageView && m_messageView->model()) {
        m_messageView->viewport()->update();
    }
}

bool ChatWindow::requestAvatar(const QString &username, bool explicitRequest) {
    return m_avatarRequests && m_avatarRequests->request(
        username, s_avatarCache.contains(username), explicitRequest);
}

// ==================== 房间设置 ====================

void ChatWindow::onRoomSettingsResponse(int roomId, bool success, qint64 maxFileSize,
                                        qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                        const QString &error,
                                        bool needConfirm, const QJsonObject &cleanupSummary,
                                        const QJsonArray &clearedFileIds, int currentMembers) {
    if (success) {
        m_roomMaxFileSize[roomId] = maxFileSize;
        m_roomTotalFileSpace[roomId] = totalFileSpace;
        m_roomMaxFileCount[roomId] = maxFileCount;
        m_roomMaxMembers[roomId] = maxMembers;

        if (!clearedFileIds.isEmpty()) {
            QList<int> ids;
            for (const QJsonValue &v : clearedFileIds) ids.append(v.toInt());
            for (auto it = m_models.begin(); it != m_models.end(); ++it) {
                it.value()->markFilesCleared(ids, QStringLiteral("文件已过期或被清除"));
                persistRoomSnapshot(it.key());
            }
        }

        if (m_waitingRoomSettingsSave) {
            m_waitingRoomSettingsSave = false;
            QMessageBox::information(this, QStringLiteral("保存成功"), QStringLiteral("房间限制修改成功"));
        }
    } else {
        if (needConfirm) {
            int clearCount = cleanupSummary["clearFileCount"].toInt();
            qint64 afterSpace = static_cast<qint64>(cleanupSummary["afterUsedSpace"].toDouble());
            int afterCount = cleanupSummary["afterFileCount"].toInt();
            QString text = QString("新限制将触发清理 %1 个历史文件。\n清理后将保留：%2 个文件，约 %3 MB。\n"
                                   "这些文件在聊天中会保留记录，但状态会标为“文件已过期或被清除”。\n是否继续？")
                               .arg(clearCount)
                               .arg(afterCount)
                               .arg(afterSpace / 1024 / 1024);
            if (QMessageBox::question(this, "确认清理", text) == QMessageBox::Yes) {
                bool keyOk = false;
                QString developerKey = QInputDialog::getText(
                    this,
                    QStringLiteral("开发者秘钥"),
                    QStringLiteral("请输入开发者秘钥以继续保存限制："),
                    QLineEdit::Password,
                    QString(),
                    &keyOk);
                if (!keyOk || developerKey.trimmed().isEmpty()) {
                    QMessageBox::warning(this, QStringLiteral("设置取消"), QStringLiteral("未输入开发者秘钥"));
                    return;
                }

                QJsonObject data;
                data["roomId"] = roomId;
                data["maxFileSize"] = static_cast<double>(maxFileSize);
                data["totalFileSpace"] = static_cast<double>(totalFileSpace);
                data["maxFileCount"] = maxFileCount;
                data["maxMembers"] = maxMembers;
                data["forceCleanup"] = true;
                data["developerKey"] = developerKey.trimmed();
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, data));
            } else {
                m_waitingRoomSettingsSave = false;
            }
            return;
        }
        m_waitingRoomSettingsSave = false;
        if (currentMembers > 0) {
            QMessageBox::warning(this, "设置失败", QString("当前人数为 %1，不能设置更小人数上限").arg(currentMembers));
            return;
        }
        QMessageBox::warning(this, "设置失败", error);
    }
}

void ChatWindow::onRoomSettingsNotify(int roomId, qint64 maxFileSize,
                                      qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                      const QJsonArray &clearedFileIds) {
    m_roomMaxFileSize[roomId] = maxFileSize;
    m_roomTotalFileSpace[roomId] = totalFileSpace;
    m_roomMaxFileCount[roomId] = maxFileCount;
    m_roomMaxMembers[roomId] = maxMembers;

    if (!clearedFileIds.isEmpty()) {
        QList<int> ids;
        for (const QJsonValue &v : clearedFileIds) ids.append(v.toInt());
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            it.value()->markFilesCleared(ids, QStringLiteral("文件已过期或被清除"));
            persistRoomSnapshot(it.key());
        }
    }
}

void ChatWindow::onRoomFilesResponse(bool success, int roomId, const QJsonArray &files,
                                     qint64 usedFileSpace, qint64 maxFileSpace, const QString &error) {
    if (!success) {
        QMessageBox::warning(this, WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale()).roomFileManagerTitle, error);
        return;
    }

    if (!m_roomFileManagerDialog) {
        m_roomFileManagerDialog = new RoomFileManagerDialog(
            this, m_windowsLocaleViewModel);
        m_roomFileManagerDialog->setAttribute(Qt::WA_DeleteOnClose);

        connect(m_roomFileManagerDialog, &QObject::destroyed, this, [this] {
            m_roomFileManagerDialog = nullptr;
        });
        connect(m_roomFileManagerDialog, &RoomFileManagerDialog::refreshRequested, this, [this](int rid) {
            QJsonObject req;
            req["roomId"] = rid;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_REQ, req));
        });
        connect(m_roomFileManagerDialog, &RoomFileManagerDialog::deleteRequested, this,
                [this](int rid, const QJsonArray &fileIds) {
            QJsonObject req;
            req["roomId"] = rid;
            req["fileIds"] = fileIds;
            req["clientOperationId"] = QUuid::createUuid().toString(QUuid::WithoutBraces);
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_DELETE_REQ, req));
        });
    }

    m_roomFileManagerDialog->setRoomInfo(roomId, usedFileSpace, maxFileSpace);
    m_roomFileManagerDialog->setFiles(files);
    m_roomFileManagerDialog->show();
    m_roomFileManagerDialog->raise();
    m_roomFileManagerDialog->activateWindow();
}

void ChatWindow::onRoomFilesDeleteResponse(bool success, int roomId, int deletedCount,
                                           const QJsonArray &clearedFileIds,
                                           qint64 usedFileSpace, qint64 maxFileSpace,
                                           const QString &error) {
    if (!success) {
        QMessageBox::warning(this, WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale()).roomFileManagerTitle, error);
        return;
    }

    QList<int> ids;
    for (const QJsonValue &v : clearedFileIds) ids.append(v.toInt());
    if (!ids.isEmpty()) {
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            it.value()->markFilesCleared(ids, WindowsLocaleCatalog::messages(
                m_windowsLocaleViewModel->locale()).roomFileClearedUnavailable);
            persistRoomSnapshot(it.key());
        }
    }

    if (m_roomFileManagerDialog) {
        m_roomFileManagerDialog->setRoomInfo(roomId, usedFileSpace, maxFileSpace);
    }

    m_statusLabel->setText(WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale()).roomFilesDeleted.arg(deletedCount));

    QJsonObject req;
    req["roomId"] = roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_REQ, req));
}

void ChatWindow::onRoomFilesNotify(int roomId, const QJsonArray &clearedFileIds,
                                   qint64 usedFileSpace, qint64 maxFileSpace,
                                   const QString &operatorName) {
    QList<int> ids;
    for (const QJsonValue &v : clearedFileIds) ids.append(v.toInt());
    if (!ids.isEmpty()) {
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            it.value()->markFilesCleared(ids, QStringLiteral("文件已过期或被清除"));
            persistRoomSnapshot(it.key());
        }
    }

    if (m_roomFileManagerDialog && roomId == m_currentRoomId) {
        m_roomFileManagerDialog->setRoomInfo(roomId, usedFileSpace, maxFileSpace);
        QJsonObject req;
        req["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_REQ, req));
    }

    if (!operatorName.isEmpty() && roomId == m_currentRoomId) {
        m_statusLabel->setText(QStringLiteral("%1 更新了房间文件").arg(operatorName));
    }
}

// ==================== 删除聊天室 ====================

void ChatWindow::onDeleteRoomResponse(bool success, int roomId, const QString &roomName, const QString &error) {
    if (success) {
        removeCachedRoom(roomId);
        QMessageBox::information(this, "删除成功", QString("聊天室 \"%1\" 已被删除").arg(roomName));
        // 从房间列表中移除
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                delete m_roomList->takeItem(i);
                break;
            }
        }
        // 如果当前正在该房间，切换到第一个房间
        if (m_currentRoomId == roomId) {
            m_currentRoomId = -1;
            m_messageView->setModel(nullptr);
            m_restoringDraft = true;
            m_inputEdit->clear();
            m_restoringDraft = false;
            if (m_roomList->count() > 0) {
                m_roomList->setCurrentRow(0);
                onRoomSelected(m_roomList->item(0));
            } else {
                m_currentRoomId = -1;
                m_messageView->setModel(nullptr);
            }
        }
        if (m_models.contains(roomId)) delete m_models.take(roomId);
    } else {
        QMessageBox::warning(this, "删除失败", error);
    }
}

void ChatWindow::onDeleteRoomNotify(int roomId, const QString &roomName, const QString &operatorName) {
    Q_UNUSED(operatorName);
    removeCachedRoom(roomId);
    // 从房间列表中移除
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            delete m_roomList->takeItem(i);
            break;
        }
    }
    // 如果当前正在该房间，切换到第一个房间
    if (m_currentRoomId == roomId) {
        QMessageBox::information(this, "聊天室已删除",
            QString("聊天室 \"%1\" 已被管理员删除").arg(roomName));
        m_currentRoomId = -1;
        m_messageView->setModel(nullptr);
        m_restoringDraft = true;
        m_inputEdit->clear();
        m_restoringDraft = false;
        if (m_roomList->count() > 0) {
            m_roomList->setCurrentRow(0);
            onRoomSelected(m_roomList->item(0));
        } else {
            m_currentRoomId = -1;
            m_messageView->setModel(nullptr);
        }
    }
    if (m_models.contains(roomId)) delete m_models.take(roomId);
}

// ==================== 重命名聊天室 ====================

void ChatWindow::onRenameRoomResponse(bool success, int roomId, const QString &newName, const QString &error) {
    if (success) {
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                m_roomList->item(i)->setText(newName);
                break;
            }
        }
        if (roomId == m_currentRoomId) {
            refreshConversationShellText();
        }

        for (RoomSettingsDialog *dlg : findChildren<RoomSettingsDialog*>()) {
            if (dlg && dlg->roomId() == roomId) {
                dlg->setRoomName(newName);
            }
        }
        QMessageBox::information(this, QStringLiteral("修改成功"), QStringLiteral("聊天室名称修改成功"));
    } else {
        QMessageBox::warning(this, "修改失败", error);
    }
}

void ChatWindow::onRenameRoomNotify(int roomId, const QString &newName) {
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            m_roomList->item(i)->setText(newName);
            break;
        }
    }
    if (roomId == m_currentRoomId) {
        refreshConversationShellText();
    }

    for (RoomSettingsDialog *dlg : findChildren<RoomSettingsDialog*>()) {
        if (dlg && dlg->roomId() == roomId) {
            dlg->setRoomName(newName);
        }
    }
}

// ==================== 聊天室密码 ====================

void ChatWindow::onSetRoomPasswordResponse(bool success, int roomId, bool hasPassword, const QString &error) {
    Q_UNUSED(roomId)
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success) {
        m_statusLabel->setText(hasPassword ? copy.roomPasswordSetStatus
                                           : copy.roomPasswordRemovedStatus);
        QMessageBox::information(this, copy.roomPasswordChangeSucceededTitle,
                                 hasPassword ? copy.roomPasswordSetSucceeded
                                             : copy.roomPasswordRemoved);
    } else {
        QMessageBox::warning(this, copy.roomPasswordSetFailedTitle, error);
    }
}

void ChatWindow::onGetRoomPasswordResponse(bool success, int roomId,
                                           bool hasPassword,
                                           const QString &error) {
    Q_UNUSED(roomId)
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success) {
        QMessageBox::information(this, copy.roomPasswordStatusTitle,
                                 hasPassword ? copy.roomPasswordPresent
                                             : copy.roomPasswordAbsent);
    } else {
        QMessageBox::warning(this, copy.roomPasswordStatusFailedTitle, error);
    }
}

void ChatWindow::onJoinRoomNeedPassword(int roomId) {
    if (m_roomPasswordPromptDialog) {
        m_roomPasswordPromptDialog->close();
        m_roomPasswordPromptDialog = nullptr;
    }
    auto *dialog = new RoomPasswordPromptDialog(
        roomId, this, m_windowsLocaleViewModel);
    m_roomPasswordPromptDialog = dialog;
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    connect(dialog, &QObject::destroyed, this, [this, dialog] {
        if (m_roomPasswordPromptDialog == dialog) {
            m_roomPasswordPromptDialog = nullptr;
        }
    });
    connect(dialog, &RoomPasswordPromptDialog::joinRequested,
            this, [](int requestedRoomId, const QString &password) {
        QJsonObject data;
        data["roomId"] = requestedRoomId;
        data["password"] = password;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_REQ, data));
    });
    dialog->open();
}

// ==================== 踢人 ====================

void ChatWindow::onKickUserResponse(bool success, int roomId, const QString &username, const QString &error) {
    Q_UNUSED(roomId)
    if (success) {
        m_statusLabel->setText(QStringLiteral("已将 %1 踢出聊天室").arg(username));
    } else {
        QMessageBox::warning(this, "踢人失败", error);
    }
}

void ChatWindow::onKickedFromRoom(int roomId, const QString &roomName, const QString &operatorName) {
    removeCachedRoom(roomId);
    // 如果当前正在该房间，切走
    if (m_currentRoomId == roomId) {
        m_currentRoomId = -1;
        refreshConversationShellText();
        m_userList->clear();
        m_messageView->setModel(nullptr);
        m_restoringDraft = true;
        m_inputEdit->clear();
        m_restoringDraft = false;
    }

    // 从房间列表移除
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            delete m_roomList->takeItem(i);
            break;
        }
    }
    m_adminRooms.remove(roomId);
    if (m_models.contains(roomId)) delete m_models.take(roomId);

    QMessageBox::warning(this, "被踢出聊天室",
        QStringLiteral("您已被管理员 %1 踢出聊天室 \"%2\"").arg(operatorName, roomName));
}

// ==================== 修改昵称 ====================

// ==================== 修改昵称 ====================

void ChatWindow::onChangeNicknameResponse(bool success, const QString &newDisplayName, const QString &error) {
    if (success) {
        m_displayName = newDisplayName;
        setWindowTitle(QString("Qt聊天室 - %1").arg(m_displayName));
        m_nicknameLabel->setText(m_displayName);
        m_statusLabel->setText(QString("昵称已修改为: %1").arg(m_displayName));
        if (m_profileDialog) m_profileDialog->updateDisplayName(m_displayName);
    } else {
        QMessageBox::warning(this, "修改昵称失败", error);
    }
}

void ChatWindow::onNicknameChangeNotify(int roomId, const QString &username, const QString &newDisplayName) {
    Q_UNUSED(roomId)
    // 更新用户列表中该用户的显示名
    QListWidgetItem *item = findUserListItem(username);
    if (item) {
        item->setData(Qt::UserRole + 3, newDisplayName);
        updateUserListItemWidget(item);
    }

    // 同步更新所有已加载模型中该用户的发送者名称
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        it.value()->updateSenderName(username, newDisplayName);
        persistRoomSnapshot(it.key());
    }
    for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
        it.value()->updateSenderName(username, newDisplayName);
        persistFriendSnapshot(it.key());
    }
}

// ==================== 修改用户ID ====================

void ChatWindow::onChangeUidResponse(bool success, const QString &oldUid, const QString &newUid, const QString &error) {
    if (success) {
        flushCurrentDraft();
        // 重命名本地缓存目录
        QString oldCacheDir = FileCache::instance()->cacheDir();
        m_username = newUid;
        FileCache::instance()->setUsername(newUid);
        QString newCacheDir = FileCache::instance()->cacheDir();

        // 如果旧缓存目录存在且新目录不存在，重命名
        if (QDir(oldCacheDir).exists() && !QDir(newCacheDir).exists()) {
            QDir().rename(oldCacheDir, newCacheDir);
        }

        // 更新所有已加载模型中自己的sender
        for (auto it = m_models.begin(); it != m_models.end(); ++it) {
            it.value()->updateSenderUid(oldUid, newUid);
        }
        for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
            it.value()->updateSenderUid(oldUid, newUid);
        }

        // uniqueId 是本地库隔离键：先将已加载快照写入新账号库，再切换句柄。
        auto newRepository = std::make_unique<LocalConversationRepository>(
            LocalConversationRepository::defaultDatabasePath(newUid));
        bool migrated = newRepository->initialize();
        if (migrated && m_localRepository) {
            migrated = m_localRepository->copyAccountTo(
                *newRepository, oldUid, newUid);
        }
        if (migrated) {
            for (auto it = m_models.cbegin(); it != m_models.cend(); ++it) {
                if (!newRepository->replaceMessages(
                        newUid, LocalConversationRepository::Kind::Room,
                        QString::number(it.key()), it.value()->messages(),
                        m_conversationSyncService->cursor(
                            roomConversation(it.key())))) {
                    migrated = false;
                    break;
                }
            }
            for (auto it = m_friendModels.cbegin();
                 migrated && it != m_friendModels.cend(); ++it) {
                if (!newRepository->replaceMessages(
                        newUid, LocalConversationRepository::Kind::Direct,
                        friendConversationKey(it.key()), it.value()->messages(),
                        m_conversationSyncService->cursor(
                            friendConversation(it.key())))) {
                    migrated = false;
                }
            }
        }
        if (migrated) {
            if (m_localRepository) {
                m_localRepository->pruneConversations(
                    oldUid, LocalConversationRepository::Kind::Room, QSet<QString>{});
                m_localRepository->pruneConversations(
                    oldUid, LocalConversationRepository::Kind::Direct, QSet<QString>{});
            }
            m_localRepository = std::move(newRepository);
        } else {
            const QString migrationError = !newRepository->lastError().isEmpty()
                ? newRepository->lastError()
                : (m_localRepository ? m_localRepository->lastError()
                                     : QStringLiteral("repository unavailable"));
            qWarning().noquote() << QStringLiteral(
                "[LocalStore] operation=migrate-account outcome=degraded detail=%1")
                .arg(migrationError);
            m_localRepository.reset();
            m_statusLabel->setText(QStringLiteral("本地消息缓存迁移失败，已切换为在线模式"));
        }
        m_outgoingMessageService->setRepository(m_localRepository.get());
        m_attachmentOutboxService->setRepository(m_localRepository.get());
        m_conversationSyncService->setContext(
            m_localRepository.get(), m_username, false);

        // 更新NetworkManager的凭证
        NetworkManager::instance()->setCredentials(
            NetworkManager::instance()->currentUserId(), newUid);

        if (m_localRepository)
            m_statusLabel->setText(QString("用户ID已修改为: %1").arg(newUid));
        if (m_profileDialog) m_profileDialog->updateUid(newUid);
        QMessageBox::information(this, "修改成功",
            QString("用户ID已从 %1 修改为 %2").arg(oldUid, newUid));
    } else {
        QMessageBox::warning(this, "修改用户ID失败", error);
    }
}

void ChatWindow::onUidChangeNotify(int roomId, const QString &oldUid, const QString &newUid, const QString &displayName) {
    Q_UNUSED(roomId)
    Q_UNUSED(displayName)

    // 更新用户列表中该用户的uniqueId
    QListWidgetItem *item = findUserListItem(oldUid);
    if (item) {
        item->setData(Qt::UserRole, newUid);
    }

    // 更新头像缓存：将旧id对应的头像映射到新id
    if (s_avatarCache.contains(oldUid)) {
        s_avatarCache[newUid] = s_avatarCache.take(oldUid);
    }

    // 更新所有已加载模型中该用户的sender
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        it.value()->updateSenderUid(oldUid, newUid);
        persistRoomSnapshot(it.key());
    }
    for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
        it.value()->updateSenderUid(oldUid, newUid);
        persistFriendSnapshot(it.key());
    }
}

// ==================== 房间设置对话框 ====================

void ChatWindow::showRoomSettingsDialog(int roomId) {
    // 获取房间名称
    QString roomName;
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            roomName = m_roomList->item(i)->text();
            break;
        }
    }

    bool isAdmin = m_adminRooms.value(roomId, false);
    qint64 maxFileSize = m_roomMaxFileSize.value(roomId, 10LL * 1024 * 1024 * 1024);
    qint64 totalFileSpace = m_roomTotalFileSpace.value(roomId, 10LL * 1024 * 1024 * 1024);
    int maxFileCount = m_roomMaxFileCount.value(roomId, 1500);
    int maxMembers = m_roomMaxMembers.value(roomId, 50);

    auto *dlg = new RoomSettingsDialog(
        roomId, roomName, isAdmin, maxFileSize, totalFileSpace,
        maxFileCount, maxMembers, this, m_windowsLocaleViewModel);
    dlg->setAttribute(Qt::WA_DeleteOnClose);

    connect(dlg, &RoomSettingsDialog::roomLimitsSaveRequested, this, [this](int) {
        m_waitingRoomSettingsSave = true;
    });

    connect(dlg, &RoomSettingsDialog::leaveRoomRequested, this, [this](int rid) {
        NetworkManager::instance()->sendMessage(Protocol::makeLeaveRoom(rid));
    });
    connect(dlg, &RoomSettingsDialog::deleteRoomRequested, this, [this](int rid, const QString &) {
        QJsonObject data;
        data["roomId"] = rid;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_REQ, data));
    });

    dlg->open();
}

void ChatWindow::showRoomFileManagerDialog(int roomId) {
    if (!m_adminRooms.value(roomId, false)) {
        const auto &copy = WindowsLocaleCatalog::messages(
            m_windowsLocaleViewModel->locale());
        QMessageBox::warning(
            this, copy.roomFileAdminRequiredTitle, copy.roomFileAdminRequired);
        return;
    }

    QJsonObject req;
    req["roomId"] = roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::ROOM_FILES_REQ, req));
}

// ==================== 个人信息对话框 ====================

void ChatWindow::showProfileDialog() {
    if (m_profileDialog) {
        m_profileDialog->raise();
        m_profileDialog->activateWindow();
        return;
    }

    QPixmap avatar = s_avatarCache.value(m_username);
    if (avatar.isNull()) requestAvatar(m_username, true);
    m_profileDialog = new ProfileDialog(
        m_userId, m_username, m_displayName, avatar, this,
        m_bandwidthViewModel.get(), m_windowsLocaleViewModel);
    m_profileDialog->setAttribute(Qt::WA_DeleteOnClose);

    connect(m_profileDialog, &ProfileDialog::changeAvatarRequested, this, &ChatWindow::onChangeAvatar);
    connect(m_profileDialog, &QObject::destroyed, this, [this] {
        m_profileDialog = nullptr;
    });

    m_profileDialog->show();
}

void ChatWindow::showUserInfoDialog(const QString &username, const QString &displayName) {
    QPixmap avatar = s_avatarCache.value(username);
    if (avatar.isNull()) requestAvatar(username, true);

    // 查找用户在当前聊天室的角色
    auto role = UserInfoDialog::Role::Member;
    if (m_userList) {
        for (int i = 0; i < m_userList->count(); ++i) {
            auto *item = m_userList->item(i);
            if (item->data(Qt::UserRole).toString() == username) {
                if (item->data(Qt::UserRole + 1).toBool())
                    role = UserInfoDialog::Role::Administrator;
                break;
            }
        }
    }

    UserInfoDialog *dlg = new UserInfoDialog(
        username, displayName, avatar, role, this, m_windowsLocaleViewModel);
    dlg->setAttribute(Qt::WA_DeleteOnClose);
    dlg->exec();
}

// ==================== 注销 ====================

void ChatWindow::onLogout() {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (QMessageBox::question(this, copy.mainLogoutTitle, copy.mainLogoutConfirm)
        != QMessageBox::Yes) return;

#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    if (m_v2ConversationDialog) m_v2ConversationDialog->close();
    if (m_accountBlockDirectoryDialog) m_accountBlockDirectoryDialog->close();
    if (m_deviceManagementDialog) m_deviceManagementDialog->close();
    if (m_deviceManagementController) m_deviceManagementController->stop();
#endif

    // 断开网络连接
    NetworkManager::instance()->disconnectFromServer();

    // 隐藏当前窗口
    hide();

    emit logoutRequested();
}

// ==================== 设置 ====================

void ChatWindow::onChangeCacheDir() {
    QString currentDir = FileCache::instance()->cacheDir();
    QString newDir = QFileDialog::getExistingDirectory(this, "选择缓存目录", currentDir);
    if (newDir.isEmpty() || newDir == currentDir) return;

    FileCache::instance()->setCacheDir(newDir, m_username);
    m_statusLabel->setText(QString("缓存目录已更改为: %1").arg(newDir));
}

void ChatWindow::showPendingAttachments() {
    if (!m_localRepository) {
        QMessageBox::information(this, QStringLiteral("待发送文件"),
                                 QStringLiteral("本地任务存储当前不可用。"));
        return;
    }

    QDialog dialog(this);
    dialog.setWindowTitle(QStringLiteral("待发送文件"));
    dialog.resize(620, 380);
    auto *layout = new QVBoxLayout(&dialog);
    auto *description = new QLabel(QStringLiteral(
        "可以重试失败任务、重新选择已变更的源文件，或取消任务。\n"
        "恢复会重新申请授权并从 0 上传，但会保留原消息 ID。"));
    description->setWordWrap(true);
    layout->addWidget(description);
    auto *list = new QListWidget(&dialog);
    layout->addWidget(list, 1);

    auto allCommands = [this] {
        auto commands = m_localRepository->attachmentCommands(
            m_username, LocalConversationRepository::Kind::Room);
        commands.append(m_localRepository->attachmentCommands(
            m_username, LocalConversationRepository::Kind::Direct));
        return commands;
    };
    auto stateText = [](LocalConversationRepository::AttachmentState state) {
        switch (state) {
        case LocalConversationRepository::AttachmentState::PendingAuthorization:
            return QStringLiteral("等待授权");
        case LocalConversationRepository::AttachmentState::Uploading:
            return QStringLiteral("上传中");
        case LocalConversationRepository::AttachmentState::Finalizing:
            return QStringLiteral("等待服务器确认");
        case LocalConversationRepository::AttachmentState::Failed:
            return QStringLiteral("失败");
        }
        return QStringLiteral("未知");
    };
    auto refresh = [list, allCommands, stateText] {
        list->clear();
        for (const auto &command : allCommands()) {
            const QString conversation = command.kind
                    == LocalConversationRepository::Kind::Room
                ? QStringLiteral("房间 %1").arg(command.conversationKey)
                : QStringLiteral("私聊 %1").arg(command.conversationKey);
            QString label = QStringLiteral("%1  ·  %2  ·  %3")
                .arg(command.fileName, conversation, stateText(command.state));
            if (!command.failureCode.isEmpty())
                label += QStringLiteral("  (%1)").arg(command.failureCode);
            auto *item = new QListWidgetItem(label, list);
            item->setData(Qt::UserRole, command.clientMessageId);
            item->setData(Qt::UserRole + 1, static_cast<int>(command.kind));
            item->setData(Qt::UserRole + 2, command.conversationKey);
            item->setToolTip(QStringLiteral("大小：%1")
                .arg(QLocale().formattedDataSize(command.fileSize)));
        }
        if (list->count() > 0) list->setCurrentRow(0);
    };
    auto selectedIdentity = [list](QString *clientMessageId,
                                   LocalConversationRepository::Kind *kind,
                                   QString *conversationKey) {
        QListWidgetItem *item = list->currentItem();
        if (!item) return false;
        *clientMessageId = item->data(Qt::UserRole).toString();
        *kind = static_cast<LocalConversationRepository::Kind>(
            item->data(Qt::UserRole + 1).toInt());
        *conversationKey = item->data(Qt::UserRole + 2).toString();
        return !clientMessageId->isEmpty();
    };
    auto resolveTarget = [this](LocalConversationRepository::Kind kind,
                                const QString &conversationKey,
                                AttachmentOutboxService::Target *target) {
        if (kind == LocalConversationRepository::Kind::Room) {
            bool ok = false;
            const int roomId = conversationKey.toInt(&ok);
            if (!ok || roomId <= 0) return false;
            bool allowed = false;
            for (int index = 0; index < m_roomList->count(); ++index) {
                if (m_roomList->item(index)->data(Qt::UserRole).toInt() == roomId) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return false;
            *target = AttachmentOutboxService::roomTarget(roomId);
            return true;
        }
        QString peer;
        bool numeric = false;
        const int friendshipId = conversationKey.toInt(&numeric);
        if (numeric && friendshipId > 0) {
            for (auto it = m_friendshipIds.cbegin(); it != m_friendshipIds.cend(); ++it) {
                if (it.value() == friendshipId) {
                    peer = it.key();
                    break;
                }
            }
        } else if (conversationKey.startsWith(QStringLiteral("peer:"))) {
            const QString candidate = conversationKey.mid(5);
            if (m_friendshipIds.contains(candidate)) peer = candidate;
        }
        if (peer.isEmpty()) return false;
        *target = AttachmentOutboxService::directTarget(conversationKey, peer);
        return true;
    };

    auto *buttons = new QHBoxLayout;
    auto *retryButton = new QPushButton(QStringLiteral("重试"), &dialog);
    auto *replaceButton = new QPushButton(QStringLiteral("重新选择源文件"), &dialog);
    auto *cancelButton = new QPushButton(QStringLiteral("取消任务"), &dialog);
    auto *closeButton = new QPushButton(QStringLiteral("关闭"), &dialog);
    buttons->addWidget(retryButton);
    buttons->addWidget(replaceButton);
    buttons->addWidget(cancelButton);
    buttons->addStretch();
    buttons->addWidget(closeButton);
    layout->addLayout(buttons);

    connect(closeButton, &QPushButton::clicked, &dialog, &QDialog::accept);
    connect(retryButton, &QPushButton::clicked, &dialog,
            [this, selectedIdentity, resolveTarget, refresh] {
        QString clientMessageId;
        QString conversationKey;
        LocalConversationRepository::Kind kind;
        if (!selectedIdentity(&clientMessageId, &kind, &conversationKey)) return;
        if (clientMessageId == m_upload.clientMessageId) {
            QMessageBox::information(this, QStringLiteral("重试文件"),
                                     QStringLiteral("该文件正在发送。"));
            return;
        }
        if (!NetworkManager::instance()->isConnected()) {
            QMessageBox::information(this, QStringLiteral("重试文件"),
                                     QStringLiteral("连接恢复后才能重试。"));
            return;
        }
        AttachmentOutboxService::Target target;
        if (!resolveTarget(kind, conversationKey, &target)) {
            QMessageBox::warning(this, QStringLiteral("重试文件"),
                                 QStringLiteral("当前没有该会话的发送权限。"));
            return;
        }
        AttachmentOutboxService::Command command;
        if (!m_attachmentOutboxService->prepareRetry(
                m_username, target, clientMessageId, &command)) {
            QMessageBox::warning(this, QStringLiteral("重试文件"),
                                 m_attachmentOutboxService->lastError());
            refresh();
            return;
        }
        enqueueAttachments({command});
        refresh();
    });
    connect(replaceButton, &QPushButton::clicked, &dialog,
            [this, selectedIdentity, resolveTarget, refresh] {
        QString clientMessageId;
        QString conversationKey;
        LocalConversationRepository::Kind kind;
        if (!selectedIdentity(&clientMessageId, &kind, &conversationKey)) return;
        if (clientMessageId == m_upload.clientMessageId) {
            QMessageBox::information(this, QStringLiteral("重新选择"),
                                     QStringLiteral("请先取消当前上传。"));
            return;
        }
        const QString path = QFileDialog::getOpenFileName(
            this, QStringLiteral("重新选择源文件"));
        if (path.isEmpty()) return;
        const qint64 size = QFileInfo(path).size();
        qint64 maximum = kind == LocalConversationRepository::Kind::Direct
            ? Protocol::MAX_FRIEND_FILE : Protocol::MAX_LARGE_FILE;
        if (kind == LocalConversationRepository::Kind::Room) {
            bool ok = false;
            const int roomId = conversationKey.toInt(&ok);
            const qint64 roomMaximum = m_roomMaxFileSize.value(roomId, maximum);
            if (ok && roomMaximum > 0) maximum = qMin(maximum, roomMaximum);
        }
        if (size <= 0 || size > maximum) {
            QMessageBox::warning(this, QStringLiteral("重新选择"),
                                 QStringLiteral("新文件为空或超过当前会话限制。"));
            return;
        }
        if (!m_attachmentOutboxService->replaceSource(
                m_username, clientMessageId, path)) {
            QMessageBox::warning(this, QStringLiteral("重新选择"),
                                 m_attachmentOutboxService->lastError());
            return;
        }
        AttachmentOutboxService::Target target;
        AttachmentOutboxService::Command command;
        if (NetworkManager::instance()->isConnected()
            && resolveTarget(kind, conversationKey, &target)
            && m_attachmentOutboxService->prepareRetry(
                m_username, target, clientMessageId, &command)) {
            enqueueAttachments({command});
        }
        refresh();
    });
    connect(cancelButton, &QPushButton::clicked, &dialog,
            [this, selectedIdentity, refresh] {
        QString clientMessageId;
        QString conversationKey;
        LocalConversationRepository::Kind kind;
        if (!selectedIdentity(&clientMessageId, &kind, &conversationKey)) return;
        Q_UNUSED(kind)
        Q_UNUSED(conversationKey)
        if (QMessageBox::question(
                this, QStringLiteral("取消文件任务"),
                QStringLiteral("确定不再发送这个文件吗？"))
            != QMessageBox::Yes) return;
        if (clientMessageId == m_upload.clientMessageId) {
            cancelUpload();
        } else {
            for (int index = m_attachmentQueue.size() - 1; index >= 0; --index) {
                if (m_attachmentQueue[index].clientMessageId == clientMessageId)
                    m_attachmentQueue.removeAt(index);
            }
            m_queuedAttachmentIds.remove(clientMessageId);
            m_attachmentOutboxService->cancel(m_username, clientMessageId);
        }
        refresh();
    });

    refresh();
    dialog.exec();
}

#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
void ChatWindow::showDeviceManagement() {
    if (!m_deviceManagementController) return;
    if (m_deviceManagementDialog) {
        m_deviceManagementDialog->raise();
        m_deviceManagementDialog->activateWindow();
        return;
    }
    m_deviceManagementDialog = new DeviceManagementDialog(
        m_deviceManagementController->viewModel(), this,
        m_windowsLocaleViewModel);
    m_deviceManagementDialog->setAttribute(Qt::WA_DeleteOnClose);
    connect(m_deviceManagementDialog, &QObject::destroyed, this, [this] {
        m_deviceManagementDialog = nullptr;
    });
    m_deviceManagementController->viewModel()->refresh();
    m_deviceManagementDialog->show();
}

void ChatWindow::showV2Conversations() {
    if (!m_deviceManagementController || !m_v2ConversationAction
            || !m_v2ConversationAction->isEnabled()
            || !m_deviceManagementController->messagingViewModel()) return;
    if (m_v2ConversationDialog) {
        m_v2ConversationDialog->raise();
        m_v2ConversationDialog->activateWindow();
        return;
    }
    m_v2ConversationDialog = new V2WindowsConversationDialog(
        m_deviceManagementController->conversationDirectoryViewModel(),
        m_deviceManagementController->messagingViewModel(),
        m_deviceManagementController->conversationParticipantViewModel(), this, true,
        m_v2MessageForwardingEnabled,
        m_deviceManagementController->messageSearchViewModel(),
        m_deviceManagementController->accountBlockViewModel(),
        m_windowsLocaleViewModel->locale(), m_windowsLocaleViewModel);
    m_v2ConversationDialog->setAttribute(Qt::WA_DeleteOnClose);
    connect(m_v2ConversationDialog, &QObject::destroyed, this, [this] {
        m_v2ConversationDialog = nullptr;
    });
    m_v2ConversationDialog->show();
}

void ChatWindow::showAccountBlockDirectory() {
    if (!m_deviceManagementController || !m_accountBlockDirectoryAction
            || !m_accountBlockDirectoryAction->isEnabled()) return;
    auto *viewModel = m_deviceManagementController->accountBlockDirectoryViewModel();
    if (!viewModel) return;
    if (m_accountBlockDirectoryDialog) {
        m_accountBlockDirectoryDialog->raise();
        m_accountBlockDirectoryDialog->activateWindow();
        return;
    }
    m_accountBlockDirectoryDialog =
        new V2WindowsAccountBlockDirectoryDialog(
            viewModel, {}, this, m_windowsLocaleViewModel);
    m_accountBlockDirectoryDialog->setAttribute(Qt::WA_DeleteOnClose);
    connect(m_accountBlockDirectoryDialog, &QObject::destroyed, this, [this] {
        m_accountBlockDirectoryDialog = nullptr;
    });
    viewModel->refresh();
    m_accountBlockDirectoryDialog->show();
}
#endif

void ChatWindow::onClearCache() {
    const qint64 mediaCacheSize = FileCache::instance()->totalCacheSize();
    const QString sizeText = QLocale().formattedDataSize(mediaCacheSize);

    auto result = QMessageBox::question(this, "清除缓存",
        QString("当前账号 [%1] 的媒体缓存为 %2\n\n"
                "清除后将删除已下载的文件、图片和本地消息历史，\n"
                "需要时会重新从服务器同步。\n"
                "草稿、发送中和发送失败的消息不会被删除。\n\n"
                "确定要清除缓存吗？")
            .arg(m_username, sizeText),
        QMessageBox::Yes | QMessageBox::No, QMessageBox::No);

    if (result != QMessageBox::Yes) return;

    flushCurrentDraft();
    if (!m_conversationSyncService->clearCachedMessages()) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=clear-account-cache outcome=failed detail=%1")
            .arg(m_conversationSyncService->lastError());
        QMessageBox::warning(this, QStringLiteral("清除缓存失败"),
                             QStringLiteral("本地消息缓存无法清除，请稍后重试。"));
        return;
    }

    for (auto it = m_models.begin(); it != m_models.end(); ++it)
        it.value()->discardCachedHistory();
    for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it)
        it.value()->discardCachedHistory();

    // 清除 QPixmapCache 中所有图片和视频缩略图
    QPixmapCache::clear();

    // 清除磁盘缓存
    FileCache::instance()->clearAllCache();

    // 重置 **所有** 房间和好友模型中文件消息的下载状态为未下载
    for (auto it = m_models.begin(); it != m_models.end(); ++it) {
        MessageModel *model = it.value();
        for (int i = 0; i < model->rowCount(); ++i) {
            const Message &msg = model->messageAt(i);
            if (msg.contentType() == Message::File && msg.fileId() != 0) {
                model->updateDownloadProgress(msg.fileId(), Message::NotDownloaded, 0.0);
            }
        }
    }
    for (auto it = m_friendModels.begin(); it != m_friendModels.end(); ++it) {
        MessageModel *model = it.value();
        for (int i = 0; i < model->rowCount(); ++i) {
            const Message &msg = model->messageAt(i);
            if (msg.contentType() == Message::File && msg.fileId() != 0) {
                model->updateDownloadProgress(msg.fileId(), Message::NotDownloaded, 0.0);
            }
        }
    }
    m_messageView->viewport()->update();

    requestCurrentRoomResume();
    requestCurrentFriendResume();

    m_statusLabel->setText(QString("已清除本地消息和 %1 媒体缓存").arg(sizeText));
}

// ==================== 好友系统 ====================

void ChatWindow::onAddFriend() {
    auto *net = NetworkManager::instance();
    FriendSearchDialog dialog(m_windowsLocaleViewModel, this);
    connect(&dialog, &FriendSearchDialog::searchRequested, &dialog,
            [net](const QString &keyword) {
        QJsonObject data;
        data["keyword"] = keyword;
        net->sendMessage(Protocol::makeMessage(Protocol::MsgType::USER_SEARCH_REQ, data));
    });
    connect(&dialog, &FriendSearchDialog::friendRequestRequested, &dialog,
            [net](const QString &username) {
        QJsonObject data;
        data["username"] = username;
        net->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FRIEND_REQUEST_REQ, data));
    });
    connect(&dialog, &FriendSearchDialog::avatarRequested, &dialog,
            [this](const QString &username) { requestAvatar(username); });
    connect(net, &NetworkManager::userSearchResponse, &dialog,
            [this, &dialog](bool success, const QJsonArray &users,
                            const QString &error) {
        if (!success) {
            dialog.showFailure(error);
            return;
        }
        QVector<FriendSearchDialog::Result> results;
        results.reserve(users.size());
        for (const QJsonValue &value : users) {
            const QJsonObject user = value.toObject();
            FriendSearchDialog::Result result;
            result.username = user["username"].toString();
            result.displayName = user["displayName"].toString();
            result.online = user["online"].toBool();
            result.currentAccount = result.username == m_username;
            for (const QJsonValue &friendValue : m_friendData) {
                if (friendValue.toObject()["username"].toString()
                        == result.username) {
                    result.alreadyFriend = true;
                    break;
                }
            }
            if (s_avatarCache.contains(result.username)) {
                result.avatar = s_avatarCache.value(result.username);
            } else {
                const QString identity = result.displayName.isEmpty()
                    ? result.username : result.displayName;
                result.avatar = generateDefaultAvatar(
                    identity, qHash(result.username));
                result.avatarNeedsRefresh = true;
            }
            results.push_back(std::move(result));
        }
        dialog.showResults(results);
    });
    connect(net, &NetworkManager::avatarGetResponse, &dialog,
            [&dialog](const QString &username, const QByteArray &avatarData) {
        if (avatarData.isEmpty()) return;
        QPixmap avatar;
        if (avatar.loadFromData(avatarData))
            dialog.updateAvatar(username, avatar);
    });
    dialog.exec();
}

void ChatWindow::onShowFriendRequests() {
    m_hasPendingFriendReq = false;
    updateUnreadDots();
    // 请求待处理列表
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FRIEND_PENDING_REQ, QJsonObject()));
}

void ChatWindow::onRefreshFriendList() {
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FRIEND_LIST_REQ, QJsonObject()));
}

void ChatWindow::onFriendSelected(QListWidgetItem *item) {
    QString friendUsername = item->data(Qt::UserRole).toString();
    QString friendDisplay  = item->data(Qt::UserRole + 1).toString();
    int friendshipId       = item->data(Qt::UserRole + 2).toInt();

    switchToFriendChat(friendUsername, friendDisplay, friendshipId);
}

void ChatWindow::onFriendContextMenu(const QPoint &pos) {
    QListWidgetItem *item = m_friendList->itemAt(pos);
    if (!item) return;

    QString friendUsername = item->data(Qt::UserRole).toString();
    QString friendDisplay  = item->data(Qt::UserRole + 1).toString();
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());

    QMenu menu(this);
    menu.addAction(copy.mainFriendViewInfo,
                   [this, friendUsername, friendDisplay] {
        showUserInfoDialog(friendUsername, friendDisplay);
    });
    if (friendUsername != m_username) {
        menu.addSeparator();
        menu.addAction(copy.mainFriendRemoveAction,
                       [this, friendUsername] {
            const auto &activeCopy = WindowsLocaleCatalog::messages(
                m_windowsLocaleViewModel->locale());
            auto r = QMessageBox::question(this, activeCopy.mainFriendRemoveTitle,
                activeCopy.mainFriendRemoveConfirm.arg(friendUsername));
            if (r != QMessageBox::Yes) return;
            QJsonObject data;
            data["username"] = friendUsername;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::FRIEND_REMOVE_REQ, data));
        });
    }
    menu.exec(m_friendList->mapToGlobal(pos));
}

void ChatWindow::onFriendRequestResponse(bool success, const QString &error) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success)
        m_statusLabel->setText(copy.mainFriendRequestSentStatus);
    else
        QMessageBox::warning(this, copy.mainFriendRequestAddTitle,
            error.isEmpty() ? copy.mainFriendRequestsFailed : error);
}

void ChatWindow::onFriendRequestNotify(const QString &fromUsername, const QString &fromDisplayName) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    const QString identity = fromDisplayName.isEmpty()
        ? fromUsername : fromDisplayName;
    m_hasPendingFriendReq = true;
    updateUnreadDots();
    if (m_trayManager)
        m_trayManager->showNotification(
            copy.mainFriendRequestNotificationTitle,
            copy.mainFriendRequestNotificationBody.arg(identity));
    m_statusLabel->setText(copy.mainFriendRequestReceivedStatus.arg(identity));
}

void ChatWindow::onFriendAcceptResponse(bool success, const QString &error) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success) {
        m_statusLabel->setText(copy.mainFriendRequestsAccepted);
        onRefreshFriendList();
        // 部分环境下数据库写入与列表查询存在极短时序差，补一次延迟刷新保证即时可见。
        QTimer::singleShot(250, this, [this] { onRefreshFriendList(); });
    } else {
        m_statusLabel->setText(error.isEmpty()
            ? copy.mainFriendRequestsFailed : error);
    }
}

void ChatWindow::onFriendAcceptNotify(const QString &username, const QString &displayName) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    const QString identity = displayName.isEmpty() ? username : displayName;
    m_statusLabel->setText(
        copy.mainFriendRequestAcceptedByStatus.arg(identity));
    onRefreshFriendList();
    QTimer::singleShot(250, this, [this] { onRefreshFriendList(); });
}

void ChatWindow::onFriendRejectResponse(bool success, const QString &error) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success)
        m_statusLabel->setText(copy.mainFriendRequestsRejected);
    else
        m_statusLabel->setText(error.isEmpty()
            ? copy.mainFriendRequestsFailed : error);
}

void ChatWindow::onFriendRemoveResponse(bool success, const QString &username, const QString &error) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    if (success) {
        m_statusLabel->setText(copy.mainFriendRemovedStatus.arg(username));
        // 如果当前正在和这个好友聊天，切回房间模式
        if (m_isFriendChat && m_currentFriendUsername == username)
            switchToRoomMode();
        removeCachedFriend(username);
        if (m_friendModels.contains(username)) delete m_friendModels.take(username);
        m_friendshipIds.remove(username);
        m_friendReadWatermarks.remove(username);
        onRefreshFriendList();
    } else {
        QMessageBox::warning(this, copy.mainFriendRemoveTitle,
            error.isEmpty() ? copy.mainFriendRemoveFailed : error);
    }
}

void ChatWindow::onFriendRemoveNotify(const QString &username, const QString &displayName) {
    const auto &copy = WindowsLocaleCatalog::messages(
        m_windowsLocaleViewModel->locale());
    const QString identity = displayName.isEmpty() ? username : displayName;
    m_statusLabel->setText(copy.mainFriendRemovedByStatus.arg(identity));
    // 如果当前正在和这个好友聊天，切回房间模式
    if (m_isFriendChat && m_currentFriendUsername == username)
        switchToRoomMode();
    removeCachedFriend(username);
    if (m_friendModels.contains(username)) delete m_friendModels.take(username);
    m_friendshipIds.remove(username);
    m_friendReadWatermarks.remove(username);
    onRefreshFriendList();
}

void ChatWindow::onFriendListReceived(const QJsonArray &friends, int pendingFriendRequests) {
    QMap<int, QString> previousFriendById;
    for (const QJsonValue &value : m_friendData) {
        const QJsonObject previous = value.toObject();
        const int friendshipId = previous["friendshipId"].toInt();
        if (friendshipId > 0)
            previousFriendById.insert(friendshipId, previous["username"].toString());
    }
    m_friendData = friends;
    m_friendList->clear();
    m_friendUnread.clear();
    m_hasPendingFriendReq = (pendingFriendRequests > 0);
    QSet<QString> allowedFriendUsernames;
    QSet<QString> allowedConversationKeys;
    QMap<QString, QString> renamedFriends;
    QMap<QString, int> nextReadWatermarks;
    m_friendshipIds.clear();

    for (const QJsonValue &v : friends) {
        QJsonObject fr = v.toObject();
        QString username = fr["username"].toString();
        const int friendshipId = fr["friendshipId"].toInt();
        allowedFriendUsernames.insert(username);
        if (friendshipId > 0) m_friendshipIds.insert(username, friendshipId);
        const int peerRead = qMax(m_friendReadWatermarks.value(username, 0),
                                  fr["peerLastReadMessageId"].toInt(0));
        nextReadWatermarks.insert(username, peerRead);
        if (m_friendModels.contains(username)
            && m_friendModels.value(username)->applyPeerReadWatermark(peerRead)) {
            persistFriendSnapshot(username);
        }
        allowedConversationKeys.insert(friendConversationKey(username));
        const QString previousUsername = previousFriendById.value(friendshipId);
        if (!previousUsername.isEmpty() && previousUsername != username)
            renamedFriends.insert(previousUsername, username);
        QString displayName = fr["displayName"].toString();
        bool isOnline = fr["isOnline"].toBool();
        int unread = fr["unread"].toInt(0);
        if (unread > 0)
            m_friendUnread[username] = unread;

        const QString identity = displayName.isEmpty() ? username : displayName;

        auto *item = new QListWidgetItem(identity);
        item->setData(Qt::UserRole,     username);
        item->setData(Qt::UserRole + 1, displayName);
        item->setData(Qt::UserRole + 2, friendshipId);
        item->setData(Qt::UserRole + 3, isOnline);

        // 头像：优先使用缓存，否则显示默认头像
        if (s_avatarCache.contains(username)) {
            item->setIcon(makeStableIcon(s_avatarCache[username]));
        } else {
            item->setIcon(makeStableIcon(
                generateDefaultAvatar(identity, qHash(username))));
        }

        m_friendList->addItem(item);
    }
    refreshFriendListPresentation();
    m_friendReadWatermarks = nextReadWatermarks;

    for (auto it = renamedFriends.cbegin(); it != renamedFriends.cend(); ++it) {
        const QString oldUsername = it.key();
        const QString newUsername = it.value();
        if (m_friendModels.contains(oldUsername) && !m_friendModels.contains(newUsername)) {
            MessageModel *model = m_friendModels.take(oldUsername);
            model->updateSenderUid(oldUsername, newUsername);
            m_friendModels.insert(newUsername, model);
            m_conversationSyncService->moveCursor(
                {LocalConversationRepository::Kind::Direct,
                 QStringLiteral("peer:%1").arg(oldUsername)},
                friendConversation(newUsername));
            if (m_friendDrafts.contains(oldUsername))
                m_friendDrafts.insert(newUsername, m_friendDrafts.take(oldUsername));
            persistFriendSnapshot(newUsername);
        }
        if (m_isFriendChat && m_currentFriendUsername == oldUsername)
            m_currentFriendUsername = newUsername;
    }
    if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
        for (const QJsonValue &value : friends) {
            const QJsonObject current = value.toObject();
            if (current["username"].toString() != m_currentFriendUsername) continue;
            m_currentFriendDisplayName = current["displayName"].toString();
            m_currentFriendshipId = current["friendshipId"].toInt();
            refreshConversationShellText();
            break;
        }
    }

    // A message may arrive before the first friend-list response. Promote that
    // temporary username-keyed snapshot to the stable friendship ID.
    if (m_localRepository) {
        for (auto it = m_friendshipIds.cbegin(); it != m_friendshipIds.cend(); ++it) {
            const QString provisionalKey = QStringLiteral("peer:%1").arg(it.key());
            const QString stableKey = QString::number(it.value());
            const auto provisional = m_localRepository->loadSnapshot(
                m_username, LocalConversationRepository::Kind::Direct, provisionalKey);
            if (provisional.messages.isEmpty() && provisional.cursor == 0
                && provisional.draft.isEmpty()) continue;
            bool promoted = m_localRepository->replaceMessages(
                m_username, LocalConversationRepository::Kind::Direct,
                stableKey, provisional.messages, provisional.cursor);
            if (promoted) {
                promoted = m_localRepository->saveDraft(
                    m_username, LocalConversationRepository::Kind::Direct,
                    stableKey, provisional.draft);
            }
            if (promoted) {
                m_localRepository->removeConversation(
                    m_username, LocalConversationRepository::Kind::Direct,
                    provisionalKey);
                m_conversationSyncService->moveCursor(
                    {LocalConversationRepository::Kind::Direct, provisionalKey},
                    {LocalConversationRepository::Kind::Direct, stableKey});
            } else {
                allowedConversationKeys.insert(provisionalKey);
            }
        }
    }

    if (m_localRepository && !m_localRepository->pruneConversations(
            m_username, LocalConversationRepository::Kind::Direct,
            allowedConversationKeys)) {
        qWarning().noquote() << QStringLiteral(
            "[LocalStore] operation=prune-direct outcome=degraded detail=%1")
            .arg(m_localRepository->lastError());
    }
    const QList<QString> cachedFriends = m_friendModels.keys();
    for (const QString &username : cachedFriends) {
        if (allowedFriendUsernames.contains(username)) continue;
        if (m_isFriendChat && m_currentFriendUsername == username)
            switchToRoomMode();
        delete m_friendModels.take(username);
        m_conversationSyncService->forget(friendConversation(username));
        m_friendDrafts.remove(username);
    }
    for (int index = m_attachmentQueue.size() - 1; index >= 0; --index) {
        const auto &command = m_attachmentQueue[index];
        if (command.target.kind == LocalConversationRepository::Kind::Direct
            && !allowedConversationKeys.contains(
                command.target.conversationKey)) {
            m_queuedAttachmentIds.remove(command.clientMessageId);
            m_attachmentQueue.removeAt(index);
        }
    }
    if (m_upload.kind == LocalConversationRepository::Kind::Direct
        && !m_upload.clientMessageId.isEmpty()
        && !allowedConversationKeys.contains(m_upload.conversationKey)) {
        cancelUpload();
    }
    retryPendingFriendSends();
    updateUnreadDots();
}

void ChatWindow::onFriendPendingReceived(const QJsonArray &requests) {
    auto *net = NetworkManager::instance();
    FriendRequestsDialog dialog(m_windowsLocaleViewModel, this);
    connect(&dialog, &FriendRequestsDialog::acceptRequested, &dialog,
            [net](int requestId, const QString &username) {
        QJsonObject data;
        data["requestId"] = requestId;
        data["fromUsername"] = username;
        net->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FRIEND_ACCEPT_REQ, data));
    });
    connect(&dialog, &FriendRequestsDialog::rejectRequested, &dialog,
            [net](int requestId) {
        QJsonObject data;
        data["requestId"] = requestId;
        net->sendMessage(Protocol::makeMessage(
            Protocol::MsgType::FRIEND_REJECT_REQ, data));
    });
    connect(&dialog, &FriendRequestsDialog::avatarRequested, &dialog,
            [this](const QString &username) { requestAvatar(username); });
    connect(net, &NetworkManager::friendAcceptResponse, &dialog,
            [&dialog](bool success, const QString &error) {
        dialog.resolveAccept(success, error);
    });
    connect(net, &NetworkManager::friendRejectResponse, &dialog,
            [&dialog](bool success, const QString &error) {
        dialog.resolveReject(success, error);
    });
    connect(net, &NetworkManager::avatarGetResponse, &dialog,
            [&dialog](const QString &username, const QByteArray &avatarData) {
        if (avatarData.isEmpty()) return;
        QPixmap avatar;
        if (avatar.loadFromData(avatarData))
            dialog.updateAvatar(username, avatar);
    });

    QVector<FriendRequestsDialog::Request> pending;
    pending.reserve(requests.size());
    for (const QJsonValue &value : requests) {
        const QJsonObject request = value.toObject();
        FriendRequestsDialog::Request row;
        row.requestId = request["requestId"].toInt();
        row.username = request["fromUsername"].toString();
        row.displayName = request["fromDisplayName"].toString();
        const QString identity = row.displayName.isEmpty()
            ? row.username : row.displayName;
        if (s_avatarCache.contains(row.username)) {
            row.avatar = s_avatarCache.value(row.username);
        } else {
            row.avatar = generateDefaultAvatar(identity, qHash(row.username));
            row.avatarNeedsRefresh = true;
        }
        pending.push_back(std::move(row));
    }
    dialog.setRequests(pending);
    dialog.exec();
}

void ChatWindow::onFriendChatMessage(const QJsonObject &data) {
    QString sender     = data["sender"].toString();
    QString senderName = data["senderName"].toString();
    QString content    = data["content"].toString();
    QString contentType = data["contentType"].toString("text");
    QString friendUsername = data["friendUsername"].toString();

    // 确定对话对象
    QString chatWith = (sender == m_username) ? friendUsername : sender;
    const int friendshipId = data["friendshipId"].toInt();
    if (friendshipId > 0) m_friendshipIds[chatWith] = friendshipId;

    MessageModel *model = getOrCreateFriendModel(chatWith);

    Message msg;
    msg.setId(data["id"].toInt());
    msg.setSender(sender);
    msg.setSenderName(senderName);
    msg.setContent(content);
    msg.setTimestamp(data["timestamp"].toVariant().toLongLong());
    msg.setSequence(data["sequence"].toVariant().toLongLong());
    msg.setClientMessageId(data["clientMessageId"].toString());
    msg.setIsMine(sender == m_username);

    if (contentType == "text")
        msg.setContentType(Message::Text);
    else if (contentType == "image")
        msg.setContentType(Message::Image);
    else if (contentType == "file")
        msg.setContentType(Message::File);

    model->addMessage(msg);
    advanceFriendSyncCursor(chatWith, syncSequenceFrom(data));
    persistFriendMessage(chatWith, msg);

    // 如果当前正在和这个好友聊天，滚动到底
    if (m_isFriendChat && m_currentFriendUsername == chatWith) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
    } else if (sender != m_username) {
        // 非当前聊天好友，增加未读计数
        m_friendUnread[chatWith] = m_friendUnread.value(chatWith, 0) + 1;
        updateUnreadDots();
    }

    // 通知
    if (sender != m_username && !isActiveWindow() && m_trayManager) {
        m_trayManager->showNotification(senderName, content);
    }
}

void ChatWindow::onFriendHistoryReceived(const QJsonObject &data) {
    const auto page = V1HistoryPageAdapter::parseDirect(data, m_username);
    if (!page.valid) {
        qWarning().noquote() << QStringLiteral(
            "[Sync] operation=parse-direct-history outcome=rejected code=%1 detail=%2")
            .arg(page.errorCode, page.error);
        m_statusLabel->setText(page.error.isEmpty()
            ? QStringLiteral("好友聊天记录同步失败") : page.error);
        return;
    }
    const QString friendUsername = page.peerUsername;
    const int friendshipId = page.friendshipId;
    if (friendshipId > 0) m_friendshipIds[friendUsername] = friendshipId;
    MessageModel *model = getOrCreateFriendModel(friendUsername);
    QList<Message> messages = page.messages;
    QList<PendingHistoryDownload> pendingDownloads;
    for (Message &message : messages) {
        PendingHistoryDownload download;
        if (prepareHistoryMedia(&message, &download))
            pendingDownloads.append(download);
    }

    if (page.sequenceMode) model->reconcileSyncPage(messages, {});
    else model->prependMessages(messages);

    const auto progress = m_conversationSyncService->applyPage(
        friendConversation(friendUsername), page.sequenceMode,
        page.observedSequences, page.nextSequence, page.hasMore);
    if (!m_conversationSyncService->lastError().isEmpty()) {
        qWarning().noquote() << QStringLiteral(
            "[Sync] operation=advance-direct-history outcome=stopped peer=%1 detail=%2")
            .arg(friendUsername, m_conversationSyncService->lastError());
        m_statusLabel->setText(QStringLiteral("好友记录续传已停止，可重新进入会话重试"));
    }
    persistFriendSnapshot(friendUsername);
    if (progress.requestNext) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeFriendHistoryAfterSequenceReq(
                friendUsername, progress.cursor));
    }

    if (m_isFriendChat && m_currentFriendUsername == friendUsername) {
        QTimer::singleShot(0, [this] {
            if (m_messageView->model() && m_messageView->model()->rowCount() > 0)
                m_messageView->scrollToBottom();
        });
    }

    // 自动下载历史中未缓存的图片
    for (const auto &download : pendingDownloads) {
        if (model->findMessageByFileId(download.fileId) >= 0
            && !FileCache::instance()->isCached(download.fileId)) {
            triggerFileDownload(download.fileId, download.fileName,
                                download.fileSize);
        }
    }
}

void ChatWindow::onFriendFileNotify(const QJsonObject &data) {
    QString sender     = data["sender"].toString();
    QString friendUsername = data["friendUsername"].toString();
    QString chatWith = (sender == m_username) ? friendUsername : sender;
    const int friendshipId = data["friendshipId"].toInt();
    if (friendshipId > 0) m_friendshipIds[chatWith] = friendshipId;

    MessageModel *model = getOrCreateFriendModel(chatWith);

    int fileId = data["fileId"].toInt();
    QString fileName = data["fileName"].toString();
    qint64 fileSize = static_cast<qint64>(data["fileSize"].toDouble());

    Message msg;
    msg.setId(data["id"].toInt());
    msg.setSequence(data["sequence"].toVariant().toLongLong());
    msg.setClientMessageId(data["clientMessageId"].toString());
    msg.setSender(sender);
    msg.setSenderName(data["senderName"].toString());
    msg.setContent(data["content"].toString());
    msg.setTimestamp(data["timestamp"].toVariant().toLongLong());
    msg.setIsMine(sender == m_username);
    msg.setFileName(fileName);
    msg.setFileSize(fileSize);
    msg.setFileId(fileId);
    msg.setFileCleared(data["fileCleared"].toBool(false));
    msg.setClearReason(data["clearReason"].toString());

    QString ct = data["contentType"].toString("file");
    bool isImage = (ct == "image");
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    bool isVideo = (ct == "video") || vidExts.contains(QFileInfo(fileName).suffix().toLower());
    // 图片和视频统一用 File 类型，由 delegate 根据扩展名决定渲染方式
    if (isImage)       msg.setContentType(Message::File);
    else if (isVideo)  msg.setContentType(Message::File);
    else               msg.setContentType(Message::File);

    if (data.contains("thumbnail"))
        msg.setThumbnail(data["thumbnail"].toString());

    // 接收到服务器转发的视频缩略图 → 保存到本地缓存（与房间 onFileNotify 一致）
    if (isVideo && data.contains("thumbnail")) {
        QByteArray thumbData = QByteArray::fromBase64(data["thumbnail"].toString().toLatin1());
        if (!thumbData.isEmpty()) {
            QString tDir = FileCache::instance()->thumbDir();
            QString thumbPath = tDir + QString("/thumb_%1.jpg").arg(fileId);
            QFile tf(thumbPath);
            if (tf.open(QIODevice::WriteOnly)) {
                tf.write(thumbData);
                tf.close();
                qInfo() << "[FriendVideoThumb] 从服务器接收缩略图已保存:" << thumbPath;
                QPixmapCache::remove(QString("vidthumb_%1").arg(fileId));
            }
        }
    }

    // 发送者自己的文件：直接从本地复制到缓存，无需下载
    const QString clientMessageId = data["clientMessageId"].toString();
    QString sentLocalPath;
    if (sender == m_username && !clientMessageId.isEmpty())
        sentLocalPath = m_pendingSentFilesByClientId.take(clientMessageId);
    if (sentLocalPath.isEmpty() && sender == m_username
        && m_pendingSentFiles.contains(fileName)) {
        sentLocalPath = m_pendingSentFiles.take(fileName);
    }
    if (sender == m_username && !sentLocalPath.isEmpty()) {
        // 移除临时上传消息（大文件分块上传时存在临时消息，与房间 onFileNotify 一致）
        if (m_uploadingFileId != 0
            && (clientMessageId.isEmpty()
                || clientMessageId == m_upload.clientMessageId)) {
            model->removeMessageByFileId(m_uploadingFileId);
            // 清理临时缩略图
            QString tempThumb = FileCache::instance()->thumbDir()
                                + QString("/thumb_%1.jpg").arg(m_uploadingFileId);
            QFile::remove(tempThumb);
            QPixmapCache::remove(QString("vidthumb_%1").arg(m_uploadingFileId));
            m_uploadingFileId = 0;
            m_uploadingFileName.clear();
        }

        if (QFile::exists(sentLocalPath)) {
            QString cached = FileCache::instance()->cacheFromLocal(
                fileId, fileName, sentLocalPath);
            if (!cached.isEmpty()) {
                msg.setDownloadState(Message::Downloaded);
                msg.setDownloadProgress(1.0);
                // 发送者的视频缩略图：如果没有从服务器收到，从本地视频生成（与房间 onFileNotify 一致）
                if (isVideo) {
                    QString thumbPath = FileCache::instance()->thumbDir()
                                        + QString("/thumb_%1.jpg").arg(fileId);
                    if (!QFile::exists(thumbPath)) {
                        generateVideoThumbnail(fileId, cached);
                    }
                }
            }
        }
        if (!clientMessageId.isEmpty()) {
            m_attachmentOutboxService->complete(m_username, clientMessageId);
            if (clientMessageId == m_upload.clientMessageId)
                clearUploadState(false);
        }
    }

    // 已缓存则标记为已下载
    if (FileCache::instance()->isCached(fileId)) {
        msg.setDownloadState(Message::Downloaded);
        msg.setDownloadProgress(1.0);
    }

    model->addMessage(msg);
    advanceFriendSyncCursor(chatWith, syncSequenceFrom(data));
    persistFriendMessage(chatWith, msg);

    if (m_isFriendChat && m_currentFriendUsername == chatWith) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
    } else if (sender != m_username) {
        m_friendUnread[chatWith] = m_friendUnread.value(chatWith, 0) + 1;
        updateUnreadDots();
    }

    // 图片文件自动下载缓存
    if (isImage && !FileCache::instance()->isCached(fileId)) {
        triggerFileDownload(fileId, fileName, fileSize);
    }
}

void ChatWindow::onFriendOnlineNotify(const QString &username, const QString &displayName) {
    Q_UNUSED(displayName)
    // 更新好友列表中的在线状态
    for (int i = 0; i < m_friendList->count(); ++i) {
        auto *item = m_friendList->item(i);
        if (item->data(Qt::UserRole).toString() == username) {
            item->setData(Qt::UserRole + 3, true);
            refreshFriendListPresentation();
            break;
        }
    }
}

void ChatWindow::onFriendOfflineNotify(const QString &username) {
    for (int i = 0; i < m_friendList->count(); ++i) {
        auto *item = m_friendList->item(i);
        if (item->data(Qt::UserRole).toString() == username) {
            item->setData(Qt::UserRole + 3, false);
            refreshFriendListPresentation();
            break;
        }
    }
}

void ChatWindow::onFriendReadNotify(const QJsonObject &data) {
    const QString username = data["readerUsername"].toString();
    const int watermark = data["lastReadMessageId"].toInt();
    if (username.isEmpty() || watermark <= 0) return;
    m_friendReadWatermarks[username] = qMax(
        m_friendReadWatermarks.value(username, 0), watermark);
    if (m_friendModels.contains(username)
        && m_friendModels.value(username)->applyPeerReadWatermark(
            m_friendReadWatermarks.value(username))) {
        persistFriendSnapshot(username);
    }
}

void ChatWindow::onFriendFileUploadStartResponse(const QJsonObject &data) {
    if (m_upload.clientMessageId.isEmpty()
        || m_upload.kind != LocalConversationRepository::Kind::Direct)
        return;
    const QString responseClientId = data["clientMessageId"].toString();
    if (!responseClientId.isEmpty()
        && responseClientId != m_upload.clientMessageId) return;
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "文件发送", data["error"].toString());
        failActiveAttachment(data["errorCode"].toString(
                                 QStringLiteral("UPLOAD_START_REJECTED")),
                             QStringLiteral("好友文件发送失败，可稍后重试"));
        return;
    }

    m_upload.uploadId = data["uploadId"].toString();
    if (!m_attachmentOutboxService->recordUploading(
            m_username, m_upload.clientMessageId)) {
        qWarning().noquote() << QStringLiteral(
            "[AttachmentOutbox] operation=uploading outcome=degraded detail=%1")
            .arg(m_attachmentOutboxService->lastError());
    }
    const QString uploadPath = data["httpUploadPath"].toString();
    if (!uploadPath.isEmpty()) {
        if (NetworkManager::instance()->uploadRawFile(
                m_upload.uploadId, uploadPath, m_upload.filePath)) {
            m_upload.rawHttp = true;
            m_statusLabel->setText("正在通过 HTTP 上传好友文件...");
            return;
        }
        QMessageBox::warning(this, "文件发送", "无法启动 HTTP 上传，请重试");
        failActiveAttachment(QStringLiteral("HTTP_UPLOAD_START_FAILED"),
                             QStringLiteral("无法启动 HTTP 上传"));
        return;
    }
    // 旧服务端兼容路径。
    m_upload.rawHttp = false;
    sendNextChunk();
}

void ChatWindow::onSendFriendFile() {
    if (!m_isFriendChat || m_currentFriendUsername.isEmpty()) return;

    QString filePath = QFileDialog::getOpenFileName(this, "发送文件");
    if (filePath.isEmpty()) return;

    // 根据文件后缀自动判断 contentType（与房间发送一致）
    QString contentType = "file";
    QString suffix = QFileInfo(filePath).suffix().toLower();
    static const QStringList imgExts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    if (imgExts.contains(suffix)) contentType = "image";
    else if (vidExts.contains(suffix)) contentType = "video";

    sendFriendFile(filePath, contentType);
}

void ChatWindow::onSendFriendImage() {
    if (!m_isFriendChat || m_currentFriendUsername.isEmpty()) return;

    QString filePath = QFileDialog::getOpenFileName(this, "发送图片", QString(),
        "图片文件 (*.png *.jpg *.jpeg *.gif *.bmp *.webp)");
    if (filePath.isEmpty()) return;
    sendFriendFile(filePath, "image");
}

void ChatWindow::sendFriendFile(const QString &filePath, const QString &contentType) {
    QFileInfo fi(filePath);
    qint64 fileSize = fi.size();

    if (fileSize > Protocol::MAX_FRIEND_FILE) {
        QMessageBox::warning(this, "文件过大",
            QString("文件大小 %1 超过好友传输限制 (100MB)")
                .arg(QLocale().formattedDataSize(fileSize)));
        return;
    }

    stageAttachment(AttachmentOutboxService::directTarget(
                        friendConversationKey(m_currentFriendUsername),
                        m_currentFriendUsername),
                    filePath, contentType);
}

void ChatWindow::onFriendRecallResponse(bool success, int messageId, const QString &error) {
    if (!success) {
        QMessageBox::warning(this, "撤回失败", error);
        return;
    }
    // 撤回成功，立即更新 UI
    if (m_isFriendChat && !m_currentFriendUsername.isEmpty()) {
        MessageModel *model = getOrCreateFriendModel(m_currentFriendUsername);

        // 清除文件缓存
        int row = model->findMessageRow(messageId);
        if (row >= 0) {
            const Message &msg = model->messageAt(row);
            if (msg.contentType() == Message::File && msg.fileId() != 0) {
                FileCache::instance()->removeFile(msg.fileId());
                QPixmapCache::remove(QString("msgimg_%1").arg(msg.fileId()));
                QPixmapCache::remove(QString("vidthumb_%1").arg(msg.fileId()));
                QString thumbPath = FileCache::instance()->thumbDir() + QString("/thumb_%1.jpg").arg(msg.fileId());
                QFile::remove(thumbPath);
            }
        }

        model->recallMessage(messageId);
        persistFriendSnapshot(m_currentFriendUsername);
    }
}

void ChatWindow::onFriendRecallNotify(const QJsonObject &data) {
    const int messageId = data["messageId"].toInt();
    const QString friendUsername = data["friendUsername"].toString();
    const int friendshipId = data["friendshipId"].toInt();
    if (friendshipId > 0) m_friendshipIds[friendUsername] = friendshipId;
    MessageModel *model = getOrCreateFriendModel(friendUsername);

    // 清除文件缓存
    int row = model->findMessageRow(messageId);
    if (row >= 0) {
        const Message &msg = model->messageAt(row);
        if (msg.contentType() == Message::File && msg.fileId() != 0) {
            FileCache::instance()->removeFile(msg.fileId());
            QPixmapCache::remove(QString("msgimg_%1").arg(msg.fileId()));
            QPixmapCache::remove(QString("vidthumb_%1").arg(msg.fileId()));
            QString thumbPath = FileCache::instance()->thumbDir() + QString("/thumb_%1.jpg").arg(msg.fileId());
            QFile::remove(thumbPath);
        }
    }

    model->recallMessage(messageId);
    advanceFriendSyncCursor(friendUsername, syncSequenceFrom(data));
    persistFriendSnapshot(friendUsername);
}

void ChatWindow::switchToFriendChat(const QString &friendUsername, const QString &friendDisplayName, int friendshipId) {
    flushCurrentDraft();
    m_isFriendChat = true;
    m_currentFriendUsername = friendUsername;
    m_currentFriendDisplayName = friendDisplayName;
    m_currentFriendshipId = friendshipId;
    if (friendshipId > 0) m_friendshipIds[friendUsername] = friendshipId;
    m_currentRoomId = -1; // 清除房间选择

    // 清除该好友的未读计数
    m_friendUnread.remove(friendUsername);
    updateUnreadDots();

    // 通知服务器标记已读
    {
        QJsonObject markData;
        markData["friendshipId"] = friendshipId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::MARK_FRIEND_READ, markData));
    }

    // 取消房间列表选中
    m_roomList->clearSelection();

    // 更新标题
    refreshConversationShellText();
    m_roomSettingsBtn->setVisible(false);

    // 隐藏用户列表（私聊不需要）
    m_userList->clear();
    if (m_rightPanel) m_rightPanel->hide();

    // 设置模型
    MessageModel *model = getOrCreateFriendModel(friendUsername);
    restoreCurrentDraft();
    m_messageView->setUpdatesEnabled(false);
    m_messageView->setModel(model);

    const qint64 cursor = m_conversationSyncService->cursor(
        friendConversation(friendUsername));
    if (cursor > 0) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeFriendHistoryAfterSequenceReq(friendUsername, cursor));
    } else {
        QJsonObject data;
        data["friendUsername"] = friendUsername;
        data["count"] = 50;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FRIEND_HISTORY_REQ, data));
    }

    QTimer::singleShot(0, [this] {
        if (m_messageView->model() && m_messageView->model()->rowCount() > 0)
            m_messageView->scrollToBottom();
        m_messageView->setUpdatesEnabled(true);
    });
}

void ChatWindow::switchToRoomMode() {
    flushCurrentDraft();
    m_isFriendChat = false;
    m_currentFriendUsername.clear();
    m_currentFriendDisplayName.clear();
    m_currentFriendshipId = -1;
    if (m_rightPanel) m_rightPanel->show();

    if (m_currentRoomId > 0) {
        switchRoom(m_currentRoomId);
    } else {
        refreshConversationShellText();
        m_roomSettingsBtn->setVisible(false);
        m_messageView->setModel(nullptr);
        m_restoringDraft = true;
        m_inputEdit->clear();
        m_restoringDraft = false;
    }
}

MessageModel *ChatWindow::getOrCreateFriendModel(const QString &friendUsername) {
    if (!m_friendModels.contains(friendUsername)) {
        auto *model = new MessageModel(this);
        m_friendModels[friendUsername] = model;
        if (!m_username.isEmpty()) {
            const auto snapshot = m_conversationSyncService->hydrate(
                friendConversation(friendUsername));
            QList<Message> cached = snapshot.messages;
            for (Message &message : cached) {
                message.setIsMine(message.sender() == m_username);
                if (message.fileId() > 0 && FileCache::instance()->isCached(message.fileId())) {
                    message.setDownloadState(Message::Downloaded);
                    message.setDownloadProgress(1.0);
                }
            }
            if (!cached.isEmpty()) model->prependMessages(cached);
            model->applyPeerReadWatermark(
                m_friendReadWatermarks.value(friendUsername, 0));
            m_friendDrafts[friendUsername] = snapshot.draft;
        }
    }
    return m_friendModels[friendUsername];
}

QPixmap ChatWindow::generateDefaultAvatar(const QString &text, int seed, int size) {
    QPixmap pm(size, size);
    pm.fill(Qt::transparent);

    // 根据 seed 生成一个稳定的色相（类似 web 端 hashColor）
    int hue = qAbs(seed * 2654435761u) % 360; // Knuth multiplicative hash
    QColor bg = QColor::fromHsl(hue, 140, 127);

    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing);

    // 画圆形背景
    QPainterPath path;
    path.addEllipse(0, 0, size, size);
    p.setClipPath(path);
    p.fillRect(0, 0, size, size, bg);

    // 绘制首字符
    p.setPen(Qt::white);
    QFont f = p.font();
    f.setPixelSize(size * 0.5);
    f.setBold(true);
    p.setFont(f);

    QString ch = text.isEmpty() ? "?" : text.left(1).toUpper();
    p.drawText(QRect(0, 0, size, size), Qt::AlignCenter, ch);
    p.end();

    return pm;
}
