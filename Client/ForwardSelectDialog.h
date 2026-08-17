#pragma once

#include <QDialog>
#include <QSet>
#include "WindowsLocaleCatalog.h"

class QPushButton;
class QLineEdit;
class QListWidget;
class QStackedWidget;
class QLabel;
class QDialogButtonBox;
class WindowsLocaleViewModel;

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
                                 QWidget *parent = nullptr,
                                 WindowsLocaleViewModel *localeViewModel = nullptr);

    QSet<int> selectedRoomIds() const;
    QSet<QString> selectedFriendUsernames() const;
    QPushButton *friendTabForTest() const { return m_friendTabBtn; }
    QPushButton *roomTabForTest() const { return m_roomTabBtn; }
    QLineEdit *searchForTest() const { return m_searchEdit; }
    QListWidget *friendListForTest() const { return m_friendList; }
    QListWidget *roomListForTest() const { return m_roomList; }
    QDialogButtonBox *buttonsForTest() const { return m_buttonBox; }

private slots:
    void switchToFriends();
    void switchToRooms();
    void onSearchTextChanged(const QString &text);

private:
    void rebuildFriendList();
    void rebuildRoomList();
    void updateTabState();
    void applyLocale();
    QString friendTitle(const FriendTarget &friendTarget) const;
    QString roomTitle(const RoomTarget &roomTarget) const;

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
    QLabel *m_hint = nullptr;
    QDialogButtonBox *m_buttonBox = nullptr;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
    bool m_showFriends = true;
};
