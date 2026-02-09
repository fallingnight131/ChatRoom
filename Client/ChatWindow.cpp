#include "ChatWindow.h"
#include "NetworkManager.h"
#include "MessageModel.h"
#include "MessageDelegate.h"
#include "EmojiPicker.h"
#include "ThemeManager.h"
#include "TrayManager.h"
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
#include <QFileDialog>
#include <QMessageBox>
#include <QCloseEvent>
#include <QClipboard>
#include <QTimer>
#include <QJsonArray>
#include <QJsonObject>
#include <QApplication>
#include <QScreen>
#include <QScrollBar>
#include <QFile>
#include <QFileInfo>
#include <QDebug>

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
    requestRoomList();
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

    auto *roomLabel = new QLabel("聊天室");
    roomLabel->setStyleSheet("font-weight: bold; font-size: 14px; padding: 4px;");
    leftLayout->addWidget(roomLabel);

    m_roomList = new QListWidget;
    m_roomList->setMinimumWidth(160);
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
    m_emojiBtn = new QPushButton("😊");
    m_emojiBtn->setFixedSize(32, 32);
    m_emojiBtn->setToolTip("表情");

    m_imageBtn = new QPushButton("🖼");
    m_imageBtn->setFixedSize(32, 32);
    m_imageBtn->setToolTip("发送图片");

    m_fileBtn = new QPushButton("📎");
    m_fileBtn->setFixedSize(32, 32);
    m_fileBtn->setToolTip("发送文件");

    toolLayout->addWidget(m_emojiBtn);
    toolLayout->addWidget(m_imageBtn);
    toolLayout->addWidget(m_fileBtn);
    toolLayout->addStretch();
    inputLayout->addLayout(toolLayout);

    auto *sendLayout = new QHBoxLayout;
    m_inputEdit = new QTextEdit;
    m_inputEdit->setMaximumHeight(80);
    m_inputEdit->setPlaceholderText("输入消息...");
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

    auto *userLabel = new QLabel("在线用户");
    userLabel->setStyleSheet("font-weight: bold; font-size: 14px; padding: 4px;");
    rightLayout->addWidget(userLabel);

    m_userList = new QListWidget;
    m_userList->setMinimumWidth(140);
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
    fileMenu->addAction("退出(&Q)", this, &QWidget::close, QKeySequence::Quit);

    auto *viewMenu = menuBar()->addMenu("视图(&V)");
    viewMenu->addAction("切换主题(&T)", this, &ChatWindow::onToggleTheme, QKeySequence("Ctrl+T"));

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

    // 消息
    connect(net, &NetworkManager::chatMessageReceived,   this, &ChatWindow::onChatMessage);
    connect(net, &NetworkManager::systemMessageReceived, this, &ChatWindow::onSystemMessage);
    connect(net, &NetworkManager::historyReceived,       this, &ChatWindow::onHistoryReceived);

    // 文件
    connect(net, &NetworkManager::fileNotify,       this, &ChatWindow::onFileNotify);
    connect(net, &NetworkManager::fileDownloadReady, this, &ChatWindow::onFileDownloadReady);

    // 撤回
    connect(net, &NetworkManager::recallResponse, this, &ChatWindow::onRecallResponse);
    connect(net, &NetworkManager::recallNotify,   this, &ChatWindow::onRecallNotify);

    // UI 交互
    connect(m_sendBtn,     &QPushButton::clicked, this, &ChatWindow::onSendMessage);
    connect(m_emojiBtn,    &QPushButton::clicked, this, &ChatWindow::onShowEmojiPicker);
    connect(m_imageBtn,    &QPushButton::clicked, this, &ChatWindow::onSendImage);
    connect(m_fileBtn,     &QPushButton::clicked, this, &ChatWindow::onSendFile);
    connect(m_roomList,    &QListWidget::itemClicked, this, &ChatWindow::onRoomSelected);
    connect(m_emojiPicker, &EmojiPicker::emojiSelected, this, &ChatWindow::onEmojiSelected);
    connect(m_messageView, &QListView::customContextMenuRequested, this, &ChatWindow::onMessageContextMenu);
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

    // 自动加入大厅
    if (m_roomList->count() > 0) {
        int firstRoomId = m_roomList->item(0)->data(Qt::UserRole).toInt();
        NetworkManager::instance()->sendMessage(Protocol::makeJoinRoomReq(firstRoomId));
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

    int roomId = message.roomId();
    MessageModel *model = getOrCreateModel(roomId);
    model->addMessage(message);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] {
            m_messageView->scrollToBottom();
        });
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

void ChatWindow::onUserListReceived(int roomId, const QStringList &users) {
    if (roomId == m_currentRoomId) {
        m_userList->clear();
        for (const QString &u : users) {
            auto *item = new QListWidgetItem(u);
            if (u == m_username)
                item->setForeground(Qt::blue);
            m_userList->addItem(item);
        }
    }
}

void ChatWindow::onUserJoined(int roomId, const QString &username) {
    if (roomId == m_currentRoomId) {
        m_userList->addItem(username);

        // 添加系统消息
        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 加入了聊天室").arg(username));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

void ChatWindow::onUserLeft(int roomId, const QString &username) {
    if (roomId == m_currentRoomId) {
        auto items = m_userList->findItems(username, Qt::MatchExactly);
        for (auto *item : items)
            delete m_userList->takeItem(m_userList->row(item));

        Message sysMsg = Message::createSystemMessage(roomId,
            QString("%1 离开了聊天室").arg(username));
        getOrCreateModel(roomId)->addMessage(sysMsg);
    }
}

// ==================== 文件传输 ====================

void ChatWindow::onSendFile() {
    if (m_currentRoomId < 0) return;

    QString filePath = QFileDialog::getOpenFileName(this, "选择文件");
    if (filePath.isEmpty()) return;

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        QMessageBox::warning(this, "错误", "无法打开文件");
        return;
    }

    if (file.size() > 10 * 1024 * 1024) {
        QMessageBox::warning(this, "错误", "文件大小不能超过10MB");
        return;
    }

    QByteArray data = file.readAll();
    QFileInfo fi(filePath);

    QJsonObject msgData;
    msgData["roomId"]   = m_currentRoomId;
    msgData["fileName"] = fi.fileName();
    msgData["fileSize"] = static_cast<double>(fi.size());
    msgData["fileData"] = QString::fromUtf8(data.toBase64());

    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_SEND, msgData));

    m_statusLabel->setText("文件发送中...");
}

void ChatWindow::onSendImage() {
    if (m_currentRoomId < 0) return;

    QString filePath = QFileDialog::getOpenFileName(this, "选择图片",
        QString(), "图片 (*.png *.jpg *.jpeg *.gif *.bmp)");
    if (filePath.isEmpty()) return;

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) return;

    if (file.size() > 5 * 1024 * 1024) {
        QMessageBox::warning(this, "错误", "图片大小不能超过5MB");
        return;
    }

    QByteArray data = file.readAll();
    QFileInfo fi(filePath);

    QJsonObject msgData;
    msgData["roomId"]   = m_currentRoomId;
    msgData["fileName"] = fi.fileName();
    msgData["fileSize"] = static_cast<double>(fi.size());
    msgData["fileData"] = QString::fromUtf8(data.toBase64());

    NetworkManager::instance()->sendMessage(
        Protocol::makeMessage(Protocol::MsgType::FILE_SEND, msgData));
}

void ChatWindow::onFileNotify(const QJsonObject &data) {
    int roomId = data["roomId"].toInt();
    Message msg = Message::createFileMessage(
        roomId,
        data["sender"].toString(),
        data["fileName"].toString(),
        static_cast<qint64>(data["fileSize"].toDouble()),
        data["fileId"].toInt());
    msg.setId(data["id"].toInt());
    msg.setIsMine(data["sender"].toString() == m_username);

    getOrCreateModel(roomId)->addMessage(msg);

    if (roomId == m_currentRoomId) {
        QTimer::singleShot(50, [this] { m_messageView->scrollToBottom(); });
    }
    m_statusLabel->setText("文件传输完成");
}

void ChatWindow::onFileDownloadReady(const QJsonObject &data) {
    if (!data["success"].toBool()) {
        QMessageBox::warning(this, "下载失败", data["error"].toString());
        return;
    }

    QString fileName = data["fileName"].toString();
    QString savePath = QFileDialog::getSaveFileName(this, "保存文件", fileName);
    if (savePath.isEmpty()) return;

    QFile file(savePath);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QByteArray::fromBase64(data["fileData"].toString().toUtf8()));
        file.close();
        QMessageBox::information(this, "成功", "文件已保存到: " + savePath);
    }
}

// ==================== 消息撤回 ====================

void ChatWindow::onRecallMessage() {
    if (m_currentRoomId < 0) return;

    QModelIndex idx = m_messageView->currentIndex();
    if (!idx.isValid()) return;

    MessageModel *model = getOrCreateModel(m_currentRoomId);
    const Message &msg = model->messageAt(idx.row());

    if (msg.sender() != m_username) {
        QMessageBox::warning(this, "提示", "只能撤回自己的消息");
        return;
    }

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
        menu.addAction("下载文件", [this, &msg] {
            QJsonObject data;
            data["fileId"]   = msg.fileId();
            data["fileName"] = msg.fileName();
            NetworkManager::instance()->sendMessage(
                Protocol::makeMessage(Protocol::MsgType::FILE_DOWNLOAD_REQ, data));
        });
    }

    if (msg.sender() == m_username && !msg.recalled()) {
        menu.addAction("撤回消息", this, &ChatWindow::onRecallMessage);
    }

    menu.addAction("复制文本", [&msg] {
        QApplication::clipboard()->setText(msg.content());
    });

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

    // 重连后自动重新加入之前的房间
    if (m_currentRoomId > 0) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeJoinRoomReq(m_currentRoomId));
    }
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
    // 最小化到托盘而不是关闭
    if (m_trayManager && m_trayManager->isAvailable()) {
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
