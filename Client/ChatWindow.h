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
class ProfileDialog;
class RoomFileManagerDialog;

/// 主聊天窗口 —— MVC 架构的 View/Controller 层
class ChatWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit ChatWindow(QWidget *parent = nullptr);
    ~ChatWindow() override;

    void setCurrentUser(int userId, const QString &username, const QString &displayName);

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
    void onSearchRoom();
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
    void onUserJoined(int roomId, const QString &username, const QString &displayName);
    void onUserLeft(int roomId, const QString &username, const QString &displayName);
    void onUserOnline(int roomId, const QString &username, const QString &displayName);
    void onUserOffline(int roomId, const QString &username, const QString &displayName);

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
    void onDeleteMsgsResponse(bool success, int roomId, int deletedCount, const QString &mode, const QJsonArray &deletedFileIds, const QString &error);
    void onDeleteMsgsNotify(int roomId, const QString &mode, const QJsonArray &messageIds, const QJsonArray &deletedFileIds);
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
    void onClearCache();

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
    void onRoomSettingsResponse(int roomId, bool success, qint64 maxFileSize,
                                qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                                                const QString &error,
                                                                bool needConfirm, const QJsonObject &cleanupSummary,
                                                                const QJsonArray &clearedFileIds, int currentMembers);
    void onRoomSettingsNotify(int roomId, qint64 maxFileSize,
                                                            qint64 totalFileSpace, int maxFileCount, int maxMembers,
                                                            const QJsonArray &clearedFileIds);
    void onRoomFilesResponse(bool success, int roomId, const QJsonArray &files,
                             qint64 usedFileSpace, qint64 maxFileSpace, const QString &error);
    void onRoomFilesDeleteResponse(bool success, int roomId, int deletedCount,
                                   const QJsonArray &clearedFileIds,
                                   qint64 usedFileSpace, qint64 maxFileSpace,
                                   const QString &error);
    void onRoomFilesNotify(int roomId, const QJsonArray &clearedFileIds,
                           qint64 usedFileSpace, qint64 maxFileSpace, const QString &operatorName);

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

    // 昵称修改
    void onChangeNicknameResponse(bool success, const QString &newDisplayName, const QString &error);
    void onNicknameChangeNotify(int roomId, const QString &username, const QString &newDisplayName);

    // 用户ID修改
    void onChangeUidResponse(bool success, const QString &oldUid, const QString &newUid, const QString &error);
    void onUidChangeNotify(int roomId, const QString &oldUid, const QString &newUid, const QString &displayName);

    // 个人信息 / 聊天室设置
    void showProfileDialog();
    void showRoomSettingsDialog(int roomId);
    void showRoomFileManagerDialog(int roomId);
    void showUserInfoDialog(const QString &username, const QString &displayName);

    // ========== 好友系统 ==========
    void onAddFriend();
    void onShowFriendRequests();
    void onRefreshFriendList();
    void onFriendSelected(QListWidgetItem *item);
    void onFriendContextMenu(const QPoint &pos);
    void onFriendRequestResponse(bool success, const QString &error);
    void onFriendRequestNotify(const QString &fromUsername, const QString &fromDisplayName);
    void onFriendAcceptResponse(bool success, const QString &error);
    void onFriendAcceptNotify(const QString &username, const QString &displayName);
    void onFriendRejectResponse(bool success, const QString &error);
    void onFriendRemoveResponse(bool success, const QString &username, const QString &error);
    void onFriendRemoveNotify(const QString &username, const QString &displayName);
    void onFriendListReceived(const QJsonArray &friends, int pendingFriendRequests);
    void onFriendPendingReceived(const QJsonArray &requests);
    void onFriendChatMessage(const QJsonObject &data);
    void onFriendHistoryReceived(const QJsonObject &data);
    void onFriendFileNotify(const QJsonObject &data);
    void onFriendOnlineNotify(const QString &username, const QString &displayName);
    void onFriendOfflineNotify(const QString &username);
    void onFriendFileUploadStartResponse(const QJsonObject &data);
    void onSendFriendFile();
    void onSendFriendImage();
    void onFriendRecallResponse(bool success, int messageId, const QString &error);
    void onFriendRecallNotify(int messageId, const QString &friendUsername);


private:
    void setupUi();
    void setupMenuBar();
    void connectSignals();
    void switchRoom(int roomId);
    void requestRoomList();
    MessageModel *getOrCreateModel(int roomId);
    void startChunkedUpload(const QString &filePath);
    void sendNextChunk();
    void pauseUpload();
    void resumeUpload();
    void cancelUpload();
    void startChunkedDownload(int fileId, const QString &fileName, qint64 fileSize);
    void triggerFileDownload(int fileId, const QString &fileName, qint64 fileSize);
    void pauseDownload(int fileId);
    void resumeDownload(int fileId);
    void cancelDownload(int fileId);
    void processNextDownload();
    void updateAllModelsDownloadProgress(int fileId, int state, double progress);
    void onFileDownloadComplete(int fileId, const QString &fileName, const QByteArray &data);
    void generateVideoThumbnail(int fileId, const QString &videoPath);
    QByteArray generateVideoThumbnailData(const QString &videoPath);
    void cacheAvatar(const QString &username, const QByteArray &data);
    void requestAvatar(const QString &username);
    void leaveRoom(int roomId);
    void addUserListItem(const QString &username, const QString &displayName, bool isAdmin, bool isOnline);
    void updateUserListItemWidget(QListWidgetItem *item);
    QListWidgetItem* findUserListItem(const QString &username);
    void switchToFriendChat(const QString &friendUsername, const QString &friendDisplayName, int friendshipId);
    void switchToRoomMode();
    void sendFriendFile(const QString &filePath, const QString &contentType);
    MessageModel *getOrCreateFriendModel(const QString &friendUsername);
    void updateRoomListAvatars();
    void updateUnreadDots();
    static QPixmap generateDefaultAvatar(const QString &text, int seed, int size = 36);

    // --- UI 组件 ---
    QSplitter    *m_splitter       = nullptr;
    QListWidget  *m_roomList       = nullptr;
    QListView    *m_messageView    = nullptr;
    QListWidget  *m_userList       = nullptr;
    QWidget      *m_rightPanel     = nullptr;
    QTextEdit    *m_inputEdit      = nullptr;
    QPushButton  *m_sendBtn        = nullptr;
    QPushButton  *m_emojiBtn       = nullptr;
    QPushButton  *m_fileBtn        = nullptr;
    QPushButton  *m_imageBtn       = nullptr;
    QPushButton  *m_avatarBtn      = nullptr;
    QLabel       *m_statusLabel    = nullptr;
    QLabel       *m_roomTitle      = nullptr;
    QLabel       *m_nicknameLabel  = nullptr;
    QPushButton  *m_roomSettingsBtn = nullptr;
    ProfileDialog *m_profileDialog = nullptr;
    RoomFileManagerDialog *m_roomFileManagerDialog = nullptr;
    QLabel       *m_avatarPreview  = nullptr;
    EmojiPicker  *m_emojiPicker    = nullptr;
    TrayManager  *m_trayManager    = nullptr;

    // 好友系统 UI
    QPushButton  *m_tabRoomBtn     = nullptr;
    QPushButton  *m_tabFriendBtn   = nullptr;
    QStackedWidget *m_listStack    = nullptr;  // 房间列表 / 好友列表 切换
    QListWidget  *m_friendList     = nullptr;
    QWidget      *m_roomBtnPanel   = nullptr;
    QWidget      *m_friendBtnPanel = nullptr;
    QPushButton  *m_friendReqBtn   = nullptr;
    QLabel       *m_tabRoomDot     = nullptr;
    QLabel       *m_tabFriendDot   = nullptr;
    QLabel       *m_friendReqDot   = nullptr;

    // --- 数据 ---
    int     m_userId     = 0;
    QString m_username;       // uniqueId (登录标识)
    QString m_displayName;    // 显示用昵称
    int     m_currentRoomId = -1;

    QMap<int, MessageModel*>  m_models;     // roomId -> MessageModel
    MessageDelegate          *m_delegate = nullptr;
    QMap<int, bool>           m_adminRooms; // roomId -> isAdmin
    QSet<int>                 m_joinedRooms; // 已加入过的房间（用于显示加入提示）
    QMap<int, qint64>         m_roomMaxFileSize;   // roomId -> maxFileSize
    QMap<int, qint64>         m_roomTotalFileSpace; // roomId -> totalFileSpace
    QMap<int, int>            m_roomMaxFileCount;   // roomId -> maxFileCount
    QMap<int, int>            m_roomMaxMembers;     // roomId -> maxMembers

    // 好友聊天数据
    bool    m_isFriendChat = false;             // 当前是否在好友聊天模式
    QString m_currentFriendUsername;             // 当前私聊好友的用户名
    QString m_currentFriendDisplayName;          // 当前私聊好友的显示名
    int     m_currentFriendshipId = -1;          // 当前 friendshipId
    QMap<QString, MessageModel*> m_friendModels; // friendUsername -> MessageModel
    QJsonArray m_friendData;                     // 好友列表数据缓存

    // 未读消息计数
    QMap<int, int>      m_roomUnread;              // roomId -> unread count
    QMap<QString, int>  m_friendUnread;             // friendUsername -> unread count
    bool                m_hasPendingFriendReq = false; // 是否有未处理的好友申请

    // 头像缓存（静态，供 MessageDelegate 使用）
    static QMap<QString, QPixmap> s_avatarCache;

    // 聊天室头像缓存  roomId -> pixmap
    QMap<int, QPixmap> m_roomAvatarCache;

    // --- 大文件分块上传状态 ---
    struct ChunkedUpload {
        QString filePath;
        QString uploadId;
        qint64 fileSize = 0;
        qint64 offset   = 0;
        int    chunkSize = Protocol::FILE_CHUNK_SIZE;
        QByteArray thumbnailData; // 视频缩略图 JPEG 数据
    };
    ChunkedUpload m_upload;
    bool m_uploadPaused = false;
    int m_uploadingFileId = 0;   // 正在上传的消息 fileId（用于 UI 进度显示）
    QString m_uploadingFileName; // 正在上传的文件名

    // 发送文件的本地路径记录（用于收到 FILE_NOTIFY 时直接缓存）
    QMap<QString, QString> m_pendingSentFiles; // fileName -> localPath

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

    // 贴边隐藏（已移除）

    bool     m_forceQuit  = false;  // 菜单退出时强制关闭（不最小化到托盘）
};
