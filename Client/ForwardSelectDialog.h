#pragma once

#include <QDialog>
#include <QSet>

class QPushButton;
class QLineEdit;
class QListWidget;
class QStackedWidget;

class ForwardSelectDialog : public QDialog {
    Q_OBJECT
public:
    struct RoomTarget {
        int roomId = 0;
        QString roomName;
        int unread = 0;
    };

    struct FriendTarget {
        QString username;
        QString displayName;
        bool isOnline = false;
        int unread = 0;
    };

    explicit ForwardSelectDialog(const QList<RoomTarget> &rooms,
                                 const QList<FriendTarget> &friends,
                                 QWidget *parent = nullptr);

    QSet<int> selectedRoomIds() const;
    QSet<QString> selectedFriendUsernames() const;

private slots:
    void switchToFriends();
    void switchToRooms();
    void onSearchTextChanged(const QString &text);

private:
    void rebuildFriendList();
    void rebuildRoomList();
    void updateTabState();

    QList<RoomTarget> m_rooms;
    QList<FriendTarget> m_friends;

    QSet<int> m_selectedRoomIds;
    QSet<QString> m_selectedFriendUsernames;

    QPushButton *m_friendTabBtn = nullptr;
    QPushButton *m_roomTabBtn = nullptr;
    QLineEdit *m_searchEdit = nullptr;
    QStackedWidget *m_stack = nullptr;
    QListWidget *m_friendList = nullptr;
    QListWidget *m_roomList = nullptr;
    bool m_showFriends = true;
};
