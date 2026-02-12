#include "ChatWindow.h"
#include "NetworkManager.h"
#include "MessageModel.h"
#include "MessageDelegate.h"
#include "EmojiPicker.h"
#include "ThemeManager.h"
#include "TrayManager.h"
#include "FileCache.h"
#include "AvatarCropDialog.h"
#include "Protocol.h"
#include "Message.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QSplitter>
#include <QListView>
#include <QListWidget>
#include <QTextEdit>
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
#include <QScrollBar>
#include <QFile>
#include <QFileInfo>
#include <QKeyEvent>
#include <QBuffer>
#include <QPainter>
#include <QPainterPath>
#include <QDebug>

// 静态头像缓存
QMap<QString, QPixmap> ChatWindow::s_avatarCache;

QPixmap ChatWindow::avatarForUser(const QString &username) {
    return s_avatarCache.value(username);
}

// ==================== 构造/析构 ====================

ChatWindow::ChatWindow(QWidget *parent)
    : QMainWindow(parent)
{
    setWindowTitle("Qt聊天室");
    setWindowFlags(Qt::Window | Qt::WindowMinimizeButtonHint | Qt::WindowMaximizeButtonHint | Qt::WindowCloseButtonHint);
    resize(1000, 700);

    setupUi();
    setupMenuBar();
    connectSignals();

    // 贴边隐藏检测定时器
    m_edgeTimer = new QTimer(this);
    m_edgeTimer->setInterval(300);
    connect(m_edgeTimer, &QTimer::timeout, this, &ChatWindow::checkEdgeHide);
    m_edgeTimer->start();

    // 系统托盘
    m_trayManager = new TrayManager(this);

    // 应用主题
    ThemeManager::instance()->applyTheme(qApp);
}

ChatWindow::~ChatWindow() = default;

void ChatWindow::setCurrentUser(int userId, const QString &username) {
    m_userId   = userId;
    m_username = username;
    setWindowTitle(QString("Qt聊天室 - %1").arg(username));

    // 设置用户缓存目录（以用户名隔离）
    FileCache::instance()->setUsername(username);

    requestRoomList();

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
    m_avatarPreview->setText("头像");

    m_avatarBtn = new QPushButton("更换头像");
    m_avatarBtn->setFixedHeight(28);
    m_avatarBtn->setToolTip("点击更换头像");
    avatarLayout->addWidget(m_avatarPreview);
    avatarLayout->addWidget(m_avatarBtn, 1);
    leftLayout->addLayout(avatarLayout);

    auto *roomLabel = new QLabel("聊天室");
    roomLabel->setStyleSheet("font-weight: bold; font-size: 14px; padding: 4px;");
    leftLayout->addWidget(roomLabel);

    m_roomList = new QListWidget;
    m_roomList->setMinimumWidth(160);
    m_roomList->setContextMenuPolicy(Qt::CustomContextMenu);
    leftLayout->addWidget(m_roomList);

    auto *roomBtnLayout = new QHBoxLayout;
    auto *createRoomBtn = new QPushButton("创建");
    auto *joinRoomBtn   = new QPushButton("加入");
    roomBtnLayout->addWidget(createRoomBtn);
    roomBtnLayout->addWidget(joinRoomBtn);
    leftLayout->addLayout(roomBtnLayout);

    connect(createRoomBtn, &QPushButton::clicked, this, &ChatWindow::onCreateRoom);
    connect(joinRoomBtn,   &QPushButton::clicked, this, &ChatWindow::onJoinRoom);

    // --- 中间：消息区域 ---
    auto *centerPanel = new QWidget;
    auto *centerLayout = new QVBoxLayout(centerPanel);
    centerLayout->setContentsMargins(4, 4, 4, 4);

    m_roomTitle = new QLabel("请选择一个聊天室");
    m_roomTitle->setStyleSheet("font-weight: bold; font-size: 16px; padding: 8px;");
    centerLayout->addWidget(m_roomTitle);

    m_messageView = new QListView;
    m_messageView->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_messageView->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_messageView->setSelectionMode(QAbstractItemView::SingleSelection);
    m_messageView->setContextMenuPolicy(Qt::CustomContextMenu);
    m_messageView->setWordWrap(true);
    m_messageView->setSpacing(2);

    m_delegate = new MessageDelegate(this);
    m_messageView->setItemDelegate(m_delegate);
    centerLayout->addWidget(m_messageView, 1);

    // 输入区域
    auto *inputPanel = new QWidget;
    auto *inputLayout = new QVBoxLayout(inputPanel);
    inputLayout->setContentsMargins(0, 4, 0, 0);

    // 工具栏
    auto *toolLayout = new QHBoxLayout;
    m_emojiBtn = new QPushButton("表情");
    m_emojiBtn->setFixedHeight(32);
    m_emojiBtn->setToolTip("表情");

    m_imageBtn = new QPushButton("图片");
    m_imageBtn->setFixedHeight(32);
    m_imageBtn->setToolTip("发送图片");

    m_fileBtn = new QPushButton("文件");
    m_fileBtn->setFixedHeight(32);
    m_fileBtn->setToolTip("发送文件");

    toolLayout->addWidget(m_emojiBtn);
    toolLayout->addWidget(m_imageBtn);
    toolLayout->addWidget(m_fileBtn);
    toolLayout->addStretch();
    inputLayout->addLayout(toolLayout);

    auto *sendLayout = new QHBoxLayout;
    m_inputEdit = new QTextEdit;
    m_inputEdit->setMaximumHeight(80);
    m_inputEdit->setPlaceholderText("输入消息... (Enter发送, Shift+Enter换行)");
    m_inputEdit->installEventFilter(this);
    m_inputEdit->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_inputEdit, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
        QMenu *menu = m_inputEdit->createStandardContextMenu();
        menu->addSeparator();
        menu->addAction("插入换行", [this] {
            m_inputEdit->insertPlainText("\n");
        });
        menu->exec(m_inputEdit->mapToGlobal(pos));
        delete menu;
    });
    m_sendBtn = new QPushButton("发送");
    m_sendBtn->setFixedSize(80, 60);
    m_sendBtn->setStyleSheet("QPushButton { background-color: #4CAF50; color: white; "
                              "border-radius: 4px; font-size: 14px; }"
                              "QPushButton:hover { background-color: #45a049; }");

    sendLayout->addWidget(m_inputEdit, 1);
    sendLayout->addWidget(m_sendBtn);
    inputLayout->addLayout(sendLayout);

    centerLayout->addWidget(inputPanel);

    // --- 右侧：用户列表 ---
    auto *rightPanel = new QWidget;
    auto *rightLayout = new QVBoxLayout(rightPanel);
    rightLayout->setContentsMargins(4, 4, 4, 4);

    auto *userLabel = new QLabel("聊天室成员");
    userLabel->setStyleSheet("font-weight: bold; font-size: 14px; padding: 4px;");
    rightLayout->addWidget(userLabel);

    m_userList = new QListWidget;
    m_userList->setMinimumWidth(140);
    m_userList->setContextMenuPolicy(Qt::CustomContextMenu);
    rightLayout->addWidget(m_userList);

    // 组装
    m_splitter->addWidget(leftPanel);
    m_splitter->addWidget(centerPanel);
    m_splitter->addWidget(rightPanel);
    m_splitter->setStretchFactor(0, 1);
    m_splitter->setStretchFactor(1, 4);
    m_splitter->setStretchFactor(2, 1);

    mainLayout->addWidget(m_splitter);

    // 状态栏
    m_statusLabel = new QLabel("未连接");
    statusBar()->addPermanentWidget(m_statusLabel);

    // 表情选择器（弹窗）
    m_emojiPicker = new EmojiPicker(this);
    m_emojiPicker->hide();
}

void ChatWindow::setupMenuBar() {
    auto *fileMenu = menuBar()->addMenu("文件(&F)");
    fileMenu->addAction("注销(&L)", this, &ChatWindow::onLogout);
    fileMenu->addSeparator();
    fileMenu->addAction("退出(&Q)", [this] {
        m_forceQuit = true;
        close();
    }, QKeySequence::Quit);

    auto *viewMenu = menuBar()->addMenu("视图(&V)");
    viewMenu->addAction("切换主题(&T)", this, &ChatWindow::onToggleTheme, QKeySequence("Ctrl+T"));

    auto *settingsMenu = menuBar()->addMenu("设置(&S)");
    settingsMenu->addAction("缓存路径(&C)...", this, &ChatWindow::onChangeCacheDir);

    auto *helpMenu = menuBar()->addMenu("帮助(&H)");
    helpMenu->addAction("关于(&A)", [this] {
        QMessageBox::about(this, "关于",
                           "Qt聊天室 v1.0\n\n"
                           "基于 Qt/C++ 开发的聊天应用\n"
                           "支持群组聊天、文件传输、消息撤回等功能");
    });
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
    connect(net, &NetworkManager::systemMessageReceived, this, &ChatWindow::onSystemMessage);
    connect(net, &NetworkManager::historyReceived,       this, &ChatWindow::onHistoryReceived);

    // 文件
    connect(net, &NetworkManager::fileNotify,       this, &ChatWindow::onFileNotify);
    connect(net, &NetworkManager::fileDownloadReady, this, &ChatWindow::onFileDownloadReady);

    // 大文件分块传输
    connect(net, &NetworkManager::uploadStartResponse, this, &ChatWindow::onUploadStartResponse);
    connect(net, &NetworkManager::uploadChunkResponse, this, &ChatWindow::onUploadChunkResponse);
    connect(net, &NetworkManager::downloadChunkResponse, this, &ChatWindow::onDownloadChunkResponse);

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
    connect(m_imageBtn,    &QPushButton::clicked, this, &ChatWindow::onSendImage);
    connect(m_fileBtn,     &QPushButton::clicked, this, &ChatWindow::onSendFile);
    connect(m_avatarBtn,   &QPushButton::clicked, this, &ChatWindow::onChangeAvatar);
    connect(m_roomList,    &QListWidget::itemClicked, this, &ChatWindow::onRoomSelected);
    connect(m_roomList,    &QListWidget::customContextMenuRequested, this, &ChatWindow::onRoomContextMenu);
    connect(m_emojiPicker, &EmojiPicker::emojiSelected, this, &ChatWindow::onEmojiSelected);
    connect(m_messageView, &QListView::customContextMenuRequested, this, &ChatWindow::onMessageContextMenu);
    connect(m_userList,    &QListWidget::customContextMenuRequested, this, &ChatWindow::onUserContextMenu);

    // 头像
    connect(net, &NetworkManager::avatarUploadResponse, this, &ChatWindow::onAvatarUploadResponse);
    connect(net, &NetworkManager::avatarGetResponse,    this, &ChatWindow::onAvatarGetResponse);
    connect(net, &NetworkManager::avatarUpdateNotify,   this, &ChatWindow::onAvatarUpdateNotify);

    // 房间设置
    connect(net, &NetworkManager::roomSettingsResponse, this, &ChatWindow::onRoomSettingsResponse);
    connect(net, &NetworkManager::roomSettingsNotify,   this, &ChatWindow::onRoomSettingsNotify);

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

    // 双击打开文件/图片
    connect(m_messageView, &QListView::doubleClicked, this, [this](const QModelIndex &idx) {
        int contentType = idx.data(MessageModel::ContentTypeRole).toInt();
        if (contentType != static_cast<int>(Message::File)) return;

        int fileId = idx.data(MessageModel::FileIdRole).toInt();
        if (FileCache::instance()->isCached(fileId)) {
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
    QString name = QInputDialog::getText(this, "创建聊天室", "请输入聊天室名称:");
    if (name.trimmed().isEmpty()) return;

    NetworkManager::instance()->sendMessage(Protocol::makeCreateRoomReq(name.trimmed()));
}

void ChatWindow::onJoinRoom() {
    bool ok;
    int roomId = QInputDialog::getInt(this, "加入聊天室", "请输入房间ID:", 1, 1, 99999, 1, &ok);
    if (!ok) return;

    NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(roomId));
}

void ChatWindow::onRoomCreated(bool success, int roomId, const QString &name, const QString &error) {
    if (success) {
        auto *item = new QListWidgetItem(QString("[%1] %2").arg(roomId).arg(name));
        item->setData(Qt::UserRole, roomId);
        m_roomList->addItem(item);
        switchRoom(roomId);
    } else {
        QMessageBox::warning(this, "创建失败", error);
    }
}

void ChatWindow::onRoomJoined(bool success, int roomId, const QString &name, const QString &error) {
    if (success) {
        // 检查是否已在列表中
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId)
                goto found;
        }
        {
            auto *item = new QListWidgetItem(QString("[%1] %2").arg(roomId).arg(name));
            item->setData(Qt::UserRole, roomId);
            m_roomList->addItem(item);
        }
found:
        switchRoom(roomId);

        // 首次加入时显示自己的加入提示
        if (!m_joinedRooms.contains(roomId)) {
            m_joinedRooms.insert(roomId);
            Message sysMsg = Message::createSystemMessage(roomId,
                QString("你加入了聊天室 %1").arg(name));
            getOrCreateModel(roomId)->addMessage(sysMsg);
        }
    } else {
        QMessageBox::warning(this, "加入失败", error);
    }
}

void ChatWindow::onRoomListReceived(const QJsonArray &rooms) {
    m_roomList->clear();
    for (const QJsonValue &v : rooms) {
        QJsonObject r = v.toObject();
        int id = r["roomId"].toInt();
        QString name = r["roomName"].toString();
        auto *item = new QListWidgetItem(QString("[%1] %2").arg(id).arg(name));
        item->setData(Qt::UserRole, id);
        m_roomList->addItem(item);
    }

    // 自动加入房间：优先恢复到之前的房间，否则加入第一个
    int targetRoomId = -1;
    if (m_currentRoomId > 0) {
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == m_currentRoomId) {
                targetRoomId = m_currentRoomId;
                break;
            }
        }
    }
    if (targetRoomId < 0 && m_roomList->count() > 0) {
        targetRoomId = m_roomList->item(0)->data(Qt::UserRole).toInt();
    }
    if (targetRoomId > 0) {
        NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(targetRoomId));
    }
}

void ChatWindow::onRoomSelected(QListWidgetItem *item) {
    int roomId = item->data(Qt::UserRole).toInt();
    if (roomId != m_currentRoomId) {
        // 先加入该房间
        NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(roomId));
    }
}

void ChatWindow::switchRoom(int roomId) {
    m_currentRoomId = roomId;

    // 获取或创建模型
    MessageModel *model = getOrCreateModel(roomId);
    m_messageView->setModel(model);

    // 更新房间标题
    for (int i = 0; i < m_roomList->count(); ++i) {
        auto *item = m_roomList->item(i);
        if (item->data(Qt::UserRole).toInt() == roomId) {
            m_roomTitle->setText(item->text());
            m_roomList->setCurrentItem(item);
            break;
        }
    }

    // 请求用户列表和历史消息
    QJsonObject userData;
    userData["roomId"] = roomId;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::USER_LIST_REQ, userData));

    // 如果模型为空，请求历史
    if (model->rowCount() == 0) {
        NetworkManager::instance()->sendMessage(Protocol::makeHistoryReq(roomId, 50));
    }

    // 请求房间设置（文件大小上限等）
    {
        QJsonObject settingsReq;
        settingsReq["roomId"] = roomId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, settingsReq));
    }

    // 滚动到底部
    QTimer::singleShot(100, [this] {
        if (m_messageView->model() && m_messageView->model()->rowCount() > 0)
            m_messageView->scrollToBottom();
    });
}

MessageModel *ChatWindow::getOrCreateModel(int roomId) {
    if (!m_models.contains(roomId)) {
        auto *model = new MessageModel(this);
        m_models[roomId] = model;
    }
    return m_models[roomId];
}

// ==================== 消息处理 ====================

void ChatWindow::onSendMessage() {
    if (m_currentRoomId < 0) {
        QMessageBox::information(this, "提示", "请先加入一个聊天室");
        return;
    }

    QString text = m_inputEdit->toPlainText().trimmed();
    if (text.isEmpty()) return;

    NetworkManager::instance()->sendMessage(
        Protocol::makeChatMsg(m_currentRoomId, m_username, text));

    m_inputEdit->clear();
}

void ChatWindow::onChatMessage(const QJsonObject &msg) {
    Message message = Message::fromJson(msg);
    message.setIsMine(message.sender() == m_username);

    int roomId = message.roomId();
    MessageModel *model = getOrCreateModel(roomId);
    model->addMessage(message);

    // 如果是当前房间，滚动到底部
    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
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

void ChatWindow::onHistoryReceived(int roomId, const QJsonArray &messages) {
    MessageModel *model = getOrCreateModel(roomId);

    QList<Message> msgList;
    for (const QJsonValue &v : messages) {
        QJsonObject obj = v.toObject();
        // 将数据库记录转为 Message
        Message m;
        QJsonObject wrapper;
        wrapper["type"] = obj["contentType"].toString() == "system"
                          ? Protocol::MsgType::SYSTEM_MSG
                          : Protocol::MsgType::CHAT_MSG;
        wrapper["timestamp"] = obj["timestamp"];
        wrapper["data"] = obj;
        m = Message::fromJson(wrapper);
        m.setIsMine(m.sender() == m_username);
        msgList.append(m);
    }

    model->prependMessages(msgList);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
    }
}

// ==================== 用户列表 ====================

void ChatWindow::onUserListReceived(int roomId, const QJsonArray &users) {
    if (roomId == m_currentRoomId) {
        m_userList->clear();
        for (const QJsonValue &v : users) {
            QJsonObject userObj = v.toObject();
            QString username = userObj["username"].toString();
            bool isAdmin = userObj["isAdmin"].toBool();
            bool isOnline = userObj["isOnline"].toBool();

            addUserListItem(username, isAdmin, isOnline);
        }
    }
}

void ChatWindow::onUserJoined(int roomId, const QString &username) {
    if (roomId == m_currentRoomId) {
        // 避免重复添加
        if (!findUserListItem(username)) {
            addUserListItem(username, false, true);
        }

        // 添加系统消息：成员加入聊天室
        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 加入了聊天室").arg(username));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

void ChatWindow::onUserLeft(int roomId, const QString &username) {
    if (roomId == m_currentRoomId) {
        // 从列表中移除
        QListWidgetItem *item = findUserListItem(username);
        if (item)
            delete m_userList->takeItem(m_userList->row(item));

        // 添加系统消息：成员退出聊天室
        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 退出了聊天室").arg(username));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

// ==================== 文件传输 ====================

void ChatWindow::onSendFile() {
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

    // 大文件走分块传输
    if (fi.size() > Protocol::MAX_SMALL_FILE) {
        startChunkedUpload(filePath);
        return;
    }

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        QMessageBox::warning(this, "错误", "无法打开文件");
        return;
    }

    QByteArray data = file.readAll();
    file.close();

    QJsonObject msgData;
    msgData["roomId"]   = m_currentRoomId;
    msgData["fileName"] = fi.fileName();
    msgData["fileSize"] = static_cast<double>(fi.size());
    msgData["fileData"] = QString::fromLatin1(data.toBase64());

    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_SEND, msgData));

    m_statusLabel->setText("文件发送中...");
}

void ChatWindow::onSendImage() {
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

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) return;
    QByteArray data = file.readAll();
    file.close();

    QJsonObject msgData;
    msgData["roomId"]   = m_currentRoomId;
    msgData["fileName"] = fi.fileName();
    msgData["fileSize"] = static_cast<double>(fi.size());
    msgData["fileData"] = QString::fromLatin1(data.toBase64());

    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_SEND, msgData));

    m_statusLabel->setText("图片发送中...");
}

void ChatWindow::onFileNotify(const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    int fileId = data["fileId"].toInt();
    QString fileName = data["fileName"].toString();
    QString sender   = data["sender"].toString();

    Message msg = Message::createFileMessage(
        roomId, sender, fileName,
        static_cast<qint64>(data["fileSize"].toDouble()), fileId);
    msg.setId(data["id"].toInt());
    msg.setIsMine(sender == m_username);

    getOrCreateModel(roomId)->addMessage(msg);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] { m_messageView->scrollToBottom(); });
    }
    m_statusLabel->setText("文件传输完成");

    // 自动下载缓存文件（类似微信，接收后自动缓存）
    if (!FileCache::instance()->isCached(fileId)) {
        qint64 fSize = static_cast<qint64>(data["fileSize"].toDouble());
        if (fSize > Protocol::MAX_SMALL_FILE) {
            // 大文件用分块下载
            startChunkedDownload(fileId, fileName, fSize);
        } else {
            QJsonObject reqData;
            reqData["fileId"]   = fileId;
            reqData["fileName"] = fileName;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_REQ, reqData));
        }
    }
}

void ChatWindow::onFileDownloadReady(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        m_statusLabel->setText("文件下载失败: " + data["error"].toString());
        return;
    }

    int fileId = data["fileId"].toInt();
    QString fileName = data["fileName"].toString();
    QByteArray fileData = QByteArray::fromBase64(data["fileData"].toString().toUtf8());

    // 缓存到本地
    QString localPath = FileCache::instance()->cacheFile(fileId, fileName, fileData);
    if (!localPath.isEmpty()) {
        m_statusLabel->setText("文件已缓存: " + fileName);

        // 如果是图片，清除旧的 QPixmapCache 并强制刷新视图
        QString lower = fileName.toLower();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            // 清除旧的 pixmap 缓存，使用新文件数据
            QPixmapCache::remove(QString("msgimg_%1").arg(fileId));
            // 通知所有模型该消息数据已变化，触发 sizeHint 重新计算
            for (auto it = m_models.begin(); it != m_models.end(); ++it) {
                int row = it.value()->findMessageByFileId(fileId);
                if (row >= 0) {
                    QModelIndex idx = it.value()->index(row, 0);
                    emit it.value()->dataChanged(idx, idx);
                }
            }
            // 强制当前视图重新布局以更新行高
            m_messageView->doItemsLayout();
            m_messageView->viewport()->update();
        }
    }
}

// ==================== 大文件分块传输 ====================

void ChatWindow::startChunkedUpload(const QString &filePath) {
    QFileInfo fi(filePath);
    m_upload.filePath  = filePath;
    m_upload.fileSize  = fi.size();
    m_upload.offset    = 0;
    m_upload.uploadId.clear();

    // 发送上传开始请求
    QJsonObject data;
    data["roomId"]   = m_currentRoomId;
    data["fileName"] = fi.fileName();
    data["fileSize"] = static_cast<double>(fi.size());
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_START, data));

    m_statusLabel->setText(QString("准备上传: %1 (%2)")
        .arg(fi.fileName())
        .arg(QLocale().formattedDataSize(fi.size())));
}

void ChatWindow::onUploadStartResponse(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "上传失败", data["error"].toString());
        return;
    }
    m_upload.uploadId = data["uploadId"].toString();
    sendNextChunk();
}

void ChatWindow::sendNextChunk() {
    QFile file(m_upload.filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        QMessageBox::warning(this, "错误", "无法读取文件");
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
    int progress = static_cast<int>(m_upload.offset * 100 / m_upload.fileSize);
    m_statusLabel->setText(QString("上传中 %1%...").arg(progress));
}

void ChatWindow::onUploadChunkResponse(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "上传失败", data["error"].toString());
        return;
    }

    if (m_upload.offset >= m_upload.fileSize) {
        // 所有块发送完毕，通知服务器结束
        QJsonObject endData;
        endData["uploadId"] = m_upload.uploadId;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_UPLOAD_END, endData));
        m_statusLabel->setText("文件上传完成");
    } else {
        sendNextChunk();
    }
}

void ChatWindow::startChunkedDownload(int fileId, const QString &fileName, qint64 fileSize) {
    m_download.fileId   = fileId;
    m_download.fileName = fileName;
    m_download.fileSize = fileSize;
    m_download.offset   = 0;
    m_download.buffer.clear();
    m_download.buffer.reserve(static_cast<int>(qMin(fileSize, (qint64)100 * 1024 * 1024)));

    // 请求第一个块
    QJsonObject data;
    data["fileId"]   = fileId;
    data["offset"]   = 0.0;
    data["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, data));

    m_statusLabel->setText(QString("下载中 %1...").arg(fileName));
}

void ChatWindow::onDownloadChunkResponse(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        m_statusLabel->setText("下载失败: " + data["error"].toString());
        return;
    }

    QByteArray chunk = QByteArray::fromBase64(data["chunkData"].toString().toLatin1());
    m_download.buffer.append(chunk);
    m_download.offset += chunk.size();

    int progress = static_cast<int>(m_download.offset * 100 / m_download.fileSize);
    m_statusLabel->setText(QString("下载中 %1%...").arg(progress));

    if (m_download.offset >= m_download.fileSize) {
        // 下载完毕，缓存
        QString localPath = FileCache::instance()->cacheFile(
            m_download.fileId, m_download.fileName, m_download.buffer);
        if (!localPath.isEmpty()) {
            m_statusLabel->setText("文件已缓存: " + m_download.fileName);

            // 刷新图片预览
            QString lower = m_download.fileName.toLower();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
                QPixmapCache::remove(QString("msgimg_%1").arg(m_download.fileId));
                for (auto it = m_models.begin(); it != m_models.end(); ++it) {
                    int row = it.value()->findMessageByFileId(m_download.fileId);
                    if (row >= 0) {
                        QModelIndex idx = it.value()->index(row, 0);
                        emit it.value()->dataChanged(idx, idx);
                    }
                }
                m_messageView->doItemsLayout();
                m_messageView->viewport()->update();
            }
        }
        m_download.buffer.clear();
    } else {
        // 继续请求下一个块
        QJsonObject reqData;
        reqData["fileId"]   = m_download.fileId;
        reqData["offset"]   = static_cast<double>(m_download.offset);
        reqData["chunkSize"] = Protocol::FILE_CHUNK_SIZE;
        NetworkManager::instance()->sendMessage(
            Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_CHUNK_REQ, reqData));
    }
}

// ==================== 消息撤回 ====================

void ChatWindow::onRecallMessage() {
    if (m_currentRoomId < 0) return;

    QModelIndex idx = m_messageView->currentIndex();
    if (!idx.isValid()) return;

    MessageModel *model = getOrCreateModel(m_currentRoomId);
    const Message &msg = model->messageAt(idx.row());

    NetworkManager::instance()->sendMessage(
        Protocol::makeRecallReq(msg.id(), m_currentRoomId));
}

void ChatWindow::onRecallResponse(bool success, int messageId, const QString &error) {
    if (!success) {
        QMessageBox::warning(this, "撤回失败", error);
    }
    Q_UNUSED(messageId)
}

void ChatWindow::onRecallNotify(int messageId, int roomId, const QString &username) {
    Q_UNUSED(username)
    MessageModel *model = getOrCreateModel(roomId);
    model->recallMessage(messageId);
}

// ==================== 管理员功能 ====================

void ChatWindow::onAdminStatusChanged(int roomId, bool isAdmin) {
    m_adminRooms[roomId] = isAdmin;
    if (roomId == m_currentRoomId) {
        m_roomTitle->setText(m_roomTitle->text().remove(QStringLiteral(" [管理员]")));
        if (isAdmin) {
            m_roomTitle->setText(m_roomTitle->text() + QStringLiteral(" [管理员]"));
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

void ChatWindow::onDeleteMsgsResponse(bool success, int roomId, int deletedCount, const QString &mode, const QString &error) {
    Q_UNUSED(mode)
    if (success) {
        m_statusLabel->setText(QStringLiteral("已删除 %1 条消息").arg(deletedCount));
        // 重新加载历史消息
        MessageModel *model = getOrCreateModel(roomId);
        model->clear();
        NetworkManager::instance()->sendMessage(Protocol::makeHistoryReq(roomId, 50));
    } else {
        QMessageBox::warning(this, "删除消息失败", error);
    }
}

void ChatWindow::onDeleteMsgsNotify(int roomId, const QString &mode, const QJsonArray &messageIds) {
    MessageModel *model = getOrCreateModel(roomId);
    if (mode == "selected") {
        // 仅移除指定消息 — 简单起见直接重新加载
        model->clear();
        NetworkManager::instance()->sendMessage(Protocol::makeHistoryReq(roomId, 50));
    } else {
        // all / before / after — 直接清空并重新加载
        Q_UNUSED(messageIds)
        model->clear();
        NetworkManager::instance()->sendMessage(Protocol::makeHistoryReq(roomId, 50));
    }
    m_statusLabel->setText("管理员清理了消息记录");
}

void ChatWindow::onUserContextMenu(const QPoint &pos) {
    if (m_currentRoomId < 0) return;

    QListWidgetItem *item = m_userList->itemAt(pos);
    if (!item) return;

    QString targetUser = item->data(Qt::UserRole).toString();
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

            menu.addAction(QStringLiteral("踢出聊天室"), [this, targetUser] {
                if (QMessageBox::question(this, "确认",
                        QString("确定要将 %1 踢出聊天室吗？").arg(targetUser))
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

    // 所有用户可以退出聊天室
    menu.addAction(QStringLiteral("退出聊天室"), [this, roomId] {
        leaveRoom(roomId);
    });

    // 管理员操作
    if (m_adminRooms.value(roomId, false)) {
        menu.addSeparator();

        // 获取房间名称
        QString roomName;
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                roomName = m_roomList->item(i)->text();
                int idx = roomName.indexOf("] ");
                if (idx >= 0) roomName = roomName.mid(idx + 2);
                break;
            }
        }

        menu.addAction(QStringLiteral("修改聊天室名称..."), [this, roomId, roomName] {
            bool ok;
            QString newName = QInputDialog::getText(this, "修改聊天室名称",
                "请输入新的聊天室名称:", QLineEdit::Normal, roomName, &ok);
            if (!ok || newName.trimmed().isEmpty()) return;
            QJsonObject data;
            data["roomId"] = roomId;
            data["newName"] = newName.trimmed();
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::RENAME_ROOM_REQ, data));
        });

        menu.addAction(QStringLiteral("修改文件大小上限..."), [this, roomId] {
            double currentMB = m_roomMaxFileSize.value(roomId, 4LL * 1024 * 1024 * 1024) / (1024.0 * 1024.0);
            bool ok;
            double sizeMB = QInputDialog::getDouble(this, "设置文件大小上限",
                "请输入允许的最大文件大小（MB，0表示无限制）:",
                currentMB, 0.0, 4096.0, 0, &ok);
            if (!ok) return;
            qint64 sizeBytes = static_cast<qint64>(sizeMB * 1024 * 1024);
            QJsonObject data;
            data["roomId"] = roomId;
            data["maxFileSize"] = static_cast<double>(sizeBytes);
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::ROOM_SETTINGS_REQ, data));
        });

        menu.addAction(QStringLiteral("设置/修改密码..."), [this, roomId] {
            bool ok;
            QString password = QInputDialog::getText(this, "设置聊天室密码",
                "请输入聊天室密码（留空表示取消密码）:", QLineEdit::Normal, "", &ok);
            if (!ok) return;
            QJsonObject data;
            data["roomId"] = roomId;
            data["password"] = password;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::SET_ROOM_PASSWORD_REQ, data));
        });

        menu.addAction(QStringLiteral("查看当前密码"), [this, roomId] {
            QJsonObject data;
            data["roomId"] = roomId;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::GET_ROOM_PASSWORD_REQ, data));
        });

        menu.addSeparator();

        menu.addAction(QStringLiteral("删除聊天室"), [this, roomId, roomName] {
            QString input = QInputDialog::getText(this, "确认删除",
                QString("此操作不可恢复！\n请输入聊天室名称 \"%1\" 确认删除:").arg(roomName));
            if (input.trimmed() != roomName) {
                if (!input.isEmpty())
                    QMessageBox::warning(this, "删除失败", "输入的名称不匹配，删除已取消");
                return;
            }
            QJsonObject data;
            data["roomId"] = roomId;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_ROOM_REQ, data));
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
    if (!idx.isValid()) return;

    MessageModel *model = qobject_cast<MessageModel*>(m_messageView->model());
    if (!model) return;

    const Message &msg = model->messageAt(idx.row());

    QMenu menu(this);

    if (msg.contentType() == Message::File) {
        int fileId = msg.fileId();
        if (FileCache::instance()->isCached(fileId)) {
            menu.addAction("打开文件", [fileId] {
                QString path = FileCache::instance()->cachedFilePath(fileId);
                FileCache::openWithSystem(path);
            });
            menu.addAction("打开所在文件夹", [fileId] {
                QString path = FileCache::instance()->cachedFilePath(fileId);
                QFileInfo fi(path);
                FileCache::openWithSystem(fi.absolutePath());
            });
        } else {
            menu.addAction("下载文件", [this, &msg] {
                QJsonObject data;
                data["fileId"]   = msg.fileId();
                data["fileName"] = msg.fileName();
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_REQ, data));
            });
        }
    }

    // 普通用户可以撤回自己2分钟内的消息
    if (msg.sender() == m_username && !msg.recalled()) {
        // 检查2分钟内的消息
        int secs = msg.timestamp().secsTo(QDateTime::currentDateTime());
        if (secs <= Protocol::RECALL_TIME_LIMIT_SEC) {
            menu.addAction("撤回消息", this, &ChatWindow::onRecallMessage);
        }
    }

    menu.addAction("复制文本", [&msg] {
        QApplication::clipboard()->setText(msg.content());
    });

    // 管理员功能
    if (m_adminRooms.value(m_currentRoomId, false) && !msg.recalled()) {
        menu.addSeparator();
        QMenu *adminMenu = menu.addMenu("管理员操作");

        // 删除这条消息
        int msgId = msg.id();
        adminMenu->addAction("删除此消息", [this, msgId] {
            QJsonObject data;
            data["roomId"] = m_currentRoomId;
            data["mode"] = QStringLiteral("selected");
            QJsonArray ids;
            ids.append(msgId);
            data["messageIds"] = ids;
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
        });

        adminMenu->addSeparator();

        // 清空所有消息
        adminMenu->addAction("清空所有消息", [this] {
            if (QMessageBox::question(this, "确认", "确定要清空所有聊天记录吗？\n此操作不可恢复！")
                == QMessageBox::Yes) {
                QJsonObject data;
                data["roomId"] = m_currentRoomId;
                data["mode"] = QStringLiteral("all");
                NetworkManager::instance()->sendMessage(
                    Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
            }
        });

        // 删除N天前的消息
        adminMenu->addAction("删除N天前的消息...", [this] {
            bool ok;
            int days = QInputDialog::getInt(this, "删除旧消息",
                "删除多少天前的消息:", 7, 1, 365, 1, &ok);
            if (!ok) return;
            QDateTime cutoff = QDateTime::currentDateTime().addDays(-days);
            QJsonObject data;
            data["roomId"] = m_currentRoomId;
            data["mode"] = QStringLiteral("before");
            data["timestamp"] = static_cast<double>(cutoff.toMSecsSinceEpoch());
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
        });

        // 删除N天内的消息
        adminMenu->addAction("删除最近N天的消息...", [this] {
            bool ok;
            int days = QInputDialog::getInt(this, "删除近期消息",
                "删除最近几天的消息:", 1, 1, 365, 1, &ok);
            if (!ok) return;
            QDateTime cutoff = QDateTime::currentDateTime().addDays(-days);
            QJsonObject data;
            data["roomId"] = m_currentRoomId;
            data["mode"] = QStringLiteral("after");
            data["timestamp"] = static_cast<double>(cutoff.toMSecsSinceEpoch());
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::DELETE_MSGS_REQ, data));
        });
    }

    menu.exec(m_messageView->viewport()->mapToGlobal(pos));
}

// ==================== 主题 ====================

void ChatWindow::onToggleTheme() {
    ThemeManager::instance()->toggleTheme();
    ThemeManager::instance()->applyTheme(qApp);
}

// ==================== 连接状态 ====================

void ChatWindow::onConnected() {
    m_statusLabel->setText("已连接");
    m_statusLabel->setStyleSheet("color: green;");

    // 重连后请求房间列表，onRoomListReceived 会自动加入合适的房间
    // 不再额外发送 JOIN_ROOM_REQ，避免重复加入
    requestRoomList();
}

void ChatWindow::onDisconnected() {
    m_statusLabel->setText("已断开");
    m_statusLabel->setStyleSheet("color: red;");
}

void ChatWindow::onReconnecting(int attempt) {
    m_statusLabel->setText(QString("重连中... (第%1次)").arg(attempt));
    m_statusLabel->setStyleSheet("color: orange;");
}

// ==================== 窗口事件 ====================

void ChatWindow::closeEvent(QCloseEvent *event) {
    if (m_forceQuit) {
        // 菜单退出：断开网络并彻底退出（含系统托盘）
        NetworkManager::instance()->disconnectFromServer();
        event->accept();
        qApp->quit();
    } else if (m_trayManager && m_trayManager->isAvailable()) {
        // 点击 X：最小化到托盘
        hide();
        m_trayManager->showNotification("Qt聊天室", "程序已最小化到系统托盘");
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

void ChatWindow::onUserOnline(int roomId, const QString &username) {
    if (roomId == m_currentRoomId) {
        QListWidgetItem *item = findUserListItem(username);
        if (item) {
            item->setData(Qt::UserRole + 2, true);
            updateUserListItemWidget(item);
        }
    }
}

void ChatWindow::onUserOffline(int roomId, const QString &username) {
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
        // 从房间列表中移除
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                delete m_roomList->takeItem(i);
                break;
            }
        }
        // 清理数据
        if (m_models.contains(roomId)) {
            delete m_models.take(roomId);
        }
        m_adminRooms.remove(roomId);
        m_joinedRooms.remove(roomId);
        m_roomMaxFileSize.remove(roomId);

        // 切换到另一个房间
        if (m_currentRoomId == roomId) {
            if (m_roomList->count() > 0) {
                m_roomList->setCurrentRow(0);
                onRoomSelected(m_roomList->item(0));
            } else {
                m_currentRoomId = -1;
                m_roomTitle->setText("请选择一个聊天室");
                m_userList->clear();
                m_messageView->setModel(nullptr);
            }
        }
    }
}

// ==================== 用户列表辅助方法 ====================

void ChatWindow::addUserListItem(const QString &username, bool isAdmin, bool isOnline) {
    auto *item = new QListWidgetItem;
    item->setData(Qt::UserRole, username);
    item->setData(Qt::UserRole + 1, isAdmin);
    item->setData(Qt::UserRole + 2, isOnline);
    item->setSizeHint(QSize(0, 36));

    m_userList->addItem(item);
    updateUserListItemWidget(item);

    // 请求未缓存用户的头像
    if (!s_avatarCache.contains(username)) {
        requestAvatar(username);
    }
}

void ChatWindow::updateUserListItemWidget(QListWidgetItem *item) {
    QString username = item->data(Qt::UserRole).toString();
    bool isAdmin = item->data(Qt::UserRole + 1).toBool();
    bool isOnline = item->data(Qt::UserRole + 2).toBool();

    auto *widget = new QWidget;
    auto *layout = new QHBoxLayout(widget);
    layout->setContentsMargins(4, 4, 4, 4);
    layout->setSpacing(4);

    auto *nameLabel = new QLabel(username);
    if (isAdmin) {
        nameLabel->setStyleSheet("color: #C5A200;");
    }

    auto *statusLabel = new QLabel(isOnline ? "在线" : "离线");
    if (isOnline) {
        statusLabel->setStyleSheet("color: green; font-size: 11px;");
    } else {
        statusLabel->setStyleSheet("color: gray; font-size: 11px;");
    }
    statusLabel->setAlignment(Qt::AlignRight | Qt::AlignVCenter);

    layout->addWidget(nameLabel);
    layout->addStretch();
    layout->addWidget(statusLabel);

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

    AvatarCropDialog dlg(img, this);
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
        requestAvatar(m_username);
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
    }

    // 刷新消息列表以显示新头像
    if (m_messageView && m_messageView->model()) {
        m_messageView->viewport()->update();
    }
}

void ChatWindow::requestAvatar(const QString &username) {
    QJsonObject data;
    data["username"] = username;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::AVATAR_GET_REQ, data));
}

// ==================== 房间设置 ====================

void ChatWindow::onRoomSettingsResponse(int roomId, bool success, qint64 maxFileSize, const QString &error) {
    if (success) {
        m_roomMaxFileSize[roomId] = maxFileSize;
    } else {
        QMessageBox::warning(this, "设置失败", error);
    }
}

void ChatWindow::onRoomSettingsNotify(int roomId, qint64 maxFileSize) {
    m_roomMaxFileSize[roomId] = maxFileSize;
}

// ==================== 删除聊天室 ====================

void ChatWindow::onDeleteRoomResponse(bool success, int roomId, const QString &roomName, const QString &error) {
    if (success) {
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
            if (m_roomList->count() > 0) {
                m_roomList->setCurrentRow(0);
                onRoomSelected(m_roomList->item(0));
            } else {
                m_currentRoomId = -1;
            }
        }
    } else {
        QMessageBox::warning(this, "删除失败", error);
    }
}

void ChatWindow::onDeleteRoomNotify(int roomId, const QString &roomName, const QString &operatorName) {
    Q_UNUSED(operatorName);
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
        if (m_roomList->count() > 0) {
            m_roomList->setCurrentRow(0);
            onRoomSelected(m_roomList->item(0));
        } else {
            m_currentRoomId = -1;
        }
    }
}

// ==================== 重命名聊天室 ====================

void ChatWindow::onRenameRoomResponse(bool success, int roomId, const QString &newName, const QString &error) {
    if (success) {
        QString displayText = QString("[%1] %2").arg(roomId).arg(newName);
        for (int i = 0; i < m_roomList->count(); ++i) {
            if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
                m_roomList->item(i)->setText(displayText);
                break;
            }
        }
        if (roomId == m_currentRoomId) {
            QString title = displayText;
            if (m_adminRooms.value(roomId, false))
                title += QStringLiteral(" [管理员]");
            m_roomTitle->setText(title);
        }
    } else {
        QMessageBox::warning(this, "修改失败", error);
    }
}

void ChatWindow::onRenameRoomNotify(int roomId, const QString &newName) {
    QString displayText = QString("[%1] %2").arg(roomId).arg(newName);
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            m_roomList->item(i)->setText(displayText);
            break;
        }
    }
    if (roomId == m_currentRoomId) {
        QString title = displayText;
        if (m_adminRooms.value(roomId, false))
            title += QStringLiteral(" [管理员]");
        m_roomTitle->setText(title);
    }
}

// ==================== 聊天室密码 ====================

void ChatWindow::onSetRoomPasswordResponse(bool success, int roomId, bool hasPassword, const QString &error) {
    Q_UNUSED(roomId)
    if (success) {
        m_statusLabel->setText(hasPassword ? QStringLiteral("聊天室密码已设置")
                                           : QStringLiteral("聊天室密码已取消"));
    } else {
        QMessageBox::warning(this, "设置密码失败", error);
    }
}

void ChatWindow::onGetRoomPasswordResponse(bool success, int roomId, const QString &password, bool hasPassword, const QString &error) {
    Q_UNUSED(roomId)
    if (success) {
        if (hasPassword) {
            QMessageBox::information(this, "聊天室密码",
                QString("当前聊天室密码为: %1").arg(password));
        } else {
            QMessageBox::information(this, "聊天室密码", "当前聊天室未设置密码");
        }
    } else {
        QMessageBox::warning(this, "查看密码失败", error);
    }
}

void ChatWindow::onJoinRoomNeedPassword(int roomId) {
    bool ok;
    QString password = QInputDialog::getText(this, "需要密码",
        "该聊天室需要密码才能加入，请输入密码:", QLineEdit::Password, "", &ok);
    if (!ok || password.isEmpty()) return;

    QJsonObject data;
    data["roomId"] = roomId;
    data["password"] = password;
    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::JOIN_ROOM_REQ, data));
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
    // 如果当前正在该房间，切走
    if (m_currentRoomId == roomId) {
        m_currentRoomId = -1;
        m_roomTitle->setText("请选择一个聊天室");
        m_userList->clear();
    }

    // 从房间列表移除
    for (int i = 0; i < m_roomList->count(); ++i) {
        if (m_roomList->item(i)->data(Qt::UserRole).toInt() == roomId) {
            delete m_roomList->takeItem(i);
            break;
        }
    }
    m_adminRooms.remove(roomId);

    QMessageBox::warning(this, "被踢出聊天室",
        QStringLiteral("您已被管理员 %1 踢出聊天室 \"%2\"").arg(operatorName, roomName));
}

// ==================== 注销 ====================

void ChatWindow::onLogout() {
    if (QMessageBox::question(this, "注销", "确定要注销当前账号吗？")
        != QMessageBox::Yes) return;

    // 断开网络连接
    NetworkManager::instance()->disconnectFromServer();

    // 隐藏当前窗口
    hide();

    // 通知 main 重新显示登录界面
    // 通过发送 forceOffline 信号触发 main.cpp 的重登录流程
    emit NetworkManager::instance()->forceOffline("用户主动注销");
}

// ==================== 设置 ====================

void ChatWindow::onChangeCacheDir() {
    QString currentDir = FileCache::instance()->cacheDir();
    QString newDir = QFileDialog::getExistingDirectory(this, "选择缓存目录", currentDir);
    if (newDir.isEmpty() || newDir == currentDir) return;

    FileCache::instance()->setCacheDir(newDir, m_username);
    m_statusLabel->setText(QString("缓存目录已更改为: %1").arg(newDir));
}

// ==================== 贴边隐藏 ====================

void ChatWindow::checkEdgeHide() {
    if (!isVisible()) return;

    QScreen *screen = QApplication::primaryScreen();
    if (!screen) return;

    QRect screenRect = screen->availableGeometry();
    QRect winRect    = frameGeometry();
    QPoint cursor    = QCursor::pos();

    const int threshold = 5;  // 贴边阈值

    if (!m_edgeHidden) {
        // 检测是否贴边
        if (winRect.left() <= screenRect.left() + threshold) {
            m_edgeSide = LeftEdge;
        } else if (winRect.right() >= screenRect.right() - threshold) {
            m_edgeSide = RightEdge;
        } else if (winRect.top() <= screenRect.top() + threshold) {
            m_edgeSide = TopEdge;
        } else {
            m_edgeSide = NoEdge;
            return;
        }

        // 贴边后鼠标离开则隐藏
        if (!winRect.contains(cursor)) {
            m_edgeHidden = true;
            int showStrip = 4; // 露出4像素
            switch (m_edgeSide) {
            case LeftEdge:
                move(screenRect.left() - width() + showStrip, y());
                break;
            case RightEdge:
                move(screenRect.right() - showStrip, y());
                break;
            case TopEdge:
                move(x(), screenRect.top() - height() + showStrip);
                break;
            default: break;
            }
        }
    } else {
        // 鼠标靠近时显示
        QRect showZone = frameGeometry();
        showZone.adjust(-20, -20, 20, 20);

        if (showZone.contains(cursor)) {
            m_edgeHidden = false;
            switch (m_edgeSide) {
            case LeftEdge:
                move(screenRect.left(), y());
                break;
            case RightEdge:
                move(screenRect.right() - width(), y());
                break;
            case TopEdge:
                move(x(), screenRect.top());
                break;
            default: break;
            }
        }
    }
}
