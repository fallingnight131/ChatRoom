#pragma once

#include <QMainWindow>
#include <QMap>
#include <QSet>
#include <QJsonArray>
#include "Protocol.h"

class QListView;
class QListWidget;
class QListWidgetItem;
class QTextEdit;
class QPushButton;
class QSplitter;
class QLabel;
class QStackedWidget;
class QAction;
class QMenu;

class MessageModel;
class MessageDelegate;
class EmojiPicker;
class ThemeManager;
class TrayManager;

/// 主聊天窗口 —— MVC 架构的 View/Controller 层
class ChatWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit ChatWindow(QWidget *parent = nullptr);
    ~ChatWindow() override;

    void setCurrentUser(int userId, const QString &username);

    /// 获取用户头像缓存
    static QPixmap avatarForUser(const QString &username);

protected:
    void closeEvent(QCloseEvent *event) override;
    void moveEvent(QMoveEvent *event) override;
    void resizeEvent(QResizeEvent *event) override;
    bool nativeEvent(const QByteArray &eventType, void *message, qintptr *result) override;
    bool eventFilter(QObject *watched, QEvent *event) override;

private slots:
    // 房间操作
    void onCreateRoom();
    void onJoinRoom();
    void onRoomCreated(bool success, int roomId, const QString &name, const QString &error);
    void onRoomJoined(bool success, int roomId, const QString &name, const QString &error, bool newJoin);
    void onRoomListReceived(const QJsonArray &rooms);
    void onRoomSelected(QListWidgetItem *item);

    // 消息
    void onSendMessage();
    void onChatMessage(const QJsonObject &msg);
    void onSystemMessage(const QJsonObject &msg);
    void onHistoryReceived(int roomId, const QJsonArray &messages);

    // 用户列表
    void onUserListReceived(int roomId, const QJsonArray &users);
    void onUserJoined(int roomId, const QString &username);
    void onUserLeft(int roomId, const QString &username);
    void onUserOnline(int roomId, const QString &username);
    void onUserOffline(int roomId, const QString &username);

    // 文件
    void onSendFile();
    void onSendImage();
    void onFileNotify(const QJsonObject &data);
    void onFileDownloadReady(const QJsonObject &data);

    // 撤回
    void onRecallMessage();
    void onRecallResponse(bool success, int messageId, const QString &error);
    void onRecallNotify(int messageId, int roomId, const QString &username);

    // 管理员
    void onAdminStatusChanged(int roomId, bool isAdmin);
    void onSetAdminResponse(bool success, int roomId, const QString &username, const QString &error);
    void onDeleteMsgsResponse(bool success, int roomId, int deletedCount, const QString &mode, const QString &error);
    void onDeleteMsgsNotify(int roomId, const QString &mode, const QJsonArray &messageIds);
    void onUserContextMenu(const QPoint &pos);
    void onRoomContextMenu(const QPoint &pos);

    // 表情
    void onEmojiSelected(const QString &emoji);
    void onShowEmojiPicker();

    // 主题
    void onToggleTheme();

    // 注销
    void onLogout();

    // 设置
    void onChangeCacheDir();

    // 连接状态
    void onConnected();
    void onDisconnected();
    void onReconnecting(int attempt);

    // 消息气泡右键菜单
    void onMessageContextMenu(const QPoint &pos);

    // 大文件分块传输
    void onUploadStartResponse(const QJsonObject &data);
    void onUploadChunkResponse(const QJsonObject &data);
    void onDownloadChunkResponse(const QJsonObject &data);

    // 头像
    void onChangeAvatar();
    void onAvatarUploadResponse(bool success, const QString &error);
    void onAvatarGetResponse(const QString &username, const QByteArray &avatarData);
    void onAvatarUpdateNotify(const QString &username, const QByteArray &avatarData);

    // 房间设置
    void onRoomSettingsResponse(int roomId, bool success, qint64 maxFileSize, const QString &error);
    void onRoomSettingsNotify(int roomId, qint64 maxFileSize);

    // 退出聊天室
    void onLeaveRoomResponse(bool success, int roomId);

    // 删除聊天室
    void onDeleteRoomResponse(bool success, int roomId, const QString &roomName, const QString &error);
    void onDeleteRoomNotify(int roomId, const QString &roomName, const QString &operatorName);

    // 重命名聊天室
    void onRenameRoomResponse(bool success, int roomId, const QString &newName, const QString &error);
    void onRenameRoomNotify(int roomId, const QString &newName);

    // 聊天室密码
    void onSetRoomPasswordResponse(bool success, int roomId, bool hasPassword, const QString &error);
    void onGetRoomPasswordResponse(bool success, int roomId, const QString &password, bool hasPassword, const QString &error);
    void onJoinRoomNeedPassword(int roomId);

    // 踢人
    void onKickUserResponse(bool success, int roomId, const QString &username, const QString &error);
    void onKickedFromRoom(int roomId, const QString &roomName, const QString &operatorName);

    // 贴边隐藏
    void checkEdgeHide();

private:
    void setupUi();
    void setupMenuBar();
    void connectSignals();
    void switchRoom(int roomId);
    void requestRoomList();
    MessageModel *getOrCreateModel(int roomId);
    void startChunkedUpload(const QString &filePath);
    void sendNextChunk();
    void startChunkedDownload(int fileId, const QString &fileName, qint64 fileSize);
    void triggerFileDownload(int fileId, const QString &fileName, qint64 fileSize);
    void processNextDownload();
    void updateAllModelsDownloadProgress(int fileId, int state, double progress);
    void onFileDownloadComplete(int fileId, const QString &fileName, const QByteArray &data);
    void cacheAvatar(const QString &username, const QByteArray &data);
    void requestAvatar(const QString &username);
    void leaveRoom(int roomId);
    void addUserListItem(const QString &username, bool isAdmin, bool isOnline);
    void updateUserListItemWidget(QListWidgetItem *item);
    QListWidgetItem* findUserListItem(const QString &username);

    // --- UI 组件 ---
    QSplitter    *m_splitter       = nullptr;
    QListWidget  *m_roomList       = nullptr;
    QListView    *m_messageView    = nullptr;
    QListWidget  *m_userList       = nullptr;
    QTextEdit    *m_inputEdit      = nullptr;
    QPushButton  *m_sendBtn        = nullptr;
    QPushButton  *m_emojiBtn       = nullptr;
    QPushButton  *m_fileBtn        = nullptr;
    QPushButton  *m_imageBtn       = nullptr;
    QPushButton  *m_avatarBtn      = nullptr;
    QLabel       *m_statusLabel    = nullptr;
    QLabel       *m_roomTitle      = nullptr;
    QLabel       *m_avatarPreview  = nullptr;
    EmojiPicker  *m_emojiPicker    = nullptr;
    TrayManager  *m_trayManager    = nullptr;

    // --- 数据 ---
    int     m_userId     = 0;
    QString m_username;
    int     m_currentRoomId = -1;

    QMap<int, MessageModel*>  m_models;     // roomId -> MessageModel
    MessageDelegate          *m_delegate = nullptr;
    QMap<int, bool>           m_adminRooms; // roomId -> isAdmin
    QSet<int>                 m_joinedRooms; // 已加入过的房间（用于显示加入提示）
    QMap<int, qint64>         m_roomMaxFileSize; // roomId -> maxFileSize

    // 头像缓存（静态，供 MessageDelegate 使用）
    static QMap<QString, QPixmap> s_avatarCache;

    // --- 大文件分块上传状态 ---
    struct ChunkedUpload {
        QString filePath;
        QString uploadId;
        qint64 fileSize = 0;
        qint64 offset   = 0;
        int    chunkSize = Protocol::FILE_CHUNK_SIZE;
    };
    ChunkedUpload m_upload;

    // --- 大文件分块下载状态（支持多文件队列） ---
    struct ChunkedDownload {
        int fileId = 0;
        QString fileName;
        qint64 fileSize = 0;
        qint64 offset   = 0;
        QByteArray buffer;
    };
    QMap<int, ChunkedDownload> m_downloads;  // fileId -> download state
    QList<int> m_downloadQueue;              // 等待下载的 fileId 队列
    int m_activeDownloadId = 0;              // 当前正在分块下载的 fileId（0=无）

    // 贴边隐藏
    QTimer  *m_edgeTimer  = nullptr;
    bool     m_edgeHidden = false;
    bool     m_forceQuit  = false;  // 菜单退出时强制关闭（不最小化到托盘）
    enum EdgeSide { NoEdge, LeftEdge, RightEdge, TopEdge };
    EdgeSide m_edgeSide   = NoEdge;
};
