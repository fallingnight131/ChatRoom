#include "ForwardSelectDialog.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QLineEdit>
#include <QListWidget>
#include <QListWidgetItem>
#include <QStackedWidget>
#include <QLabel>
#include <QDialogButtonBox>

namespace {
QString buildFriendTitle(const ForwardSelectDialog::FriendTarget &f) {
    QString name = f.displayName.trimmed().isEmpty() ? f.username : f.displayName;
    QString status = f.isOnline ? QStringLiteral("在线") : QStringLiteral("离线");
    if (f.unread > 0) {
        return QStringLiteral("%1  (%2)  [%3]  未读:%4").arg(name, f.username, status).arg(f.unread);
    }
    return QStringLiteral("%1  (%2)  [%3]").arg(name, f.username, status);
}

QString buildRoomTitle(const ForwardSelectDialog::RoomTarget &r) {
    if (r.unread > 0) {
        return QStringLiteral("%1  (ID:%2)  未读:%3").arg(r.roomName).arg(r.roomId).arg(r.unread);
    }
    return QStringLiteral("%1  (ID:%2)").arg(r.roomName).arg(r.roomId);
}
}

ForwardSelectDialog::ForwardSelectDialog(const QList<RoomTarget> &rooms,
                                         const QList<FriendTarget> &friends,
                                         QWidget *parent)
    : QDialog(parent), m_rooms(rooms), m_friends(friends)
{
    setWindowTitle(QStringLiteral("转发到其他会话"));
    resize(520, 520);

    auto *mainLayout = new QVBoxLayout(this);

    auto *tabLayout = new QHBoxLayout;
    m_friendTabBtn = new QPushButton(QStringLiteral("好友"));
    m_roomTabBtn = new QPushButton(QStringLiteral("房间"));
    m_friendTabBtn->setCheckable(true);
    m_roomTabBtn->setCheckable(true);
    tabLayout->addWidget(m_friendTabBtn);
    tabLayout->addWidget(m_roomTabBtn);
    mainLayout->addLayout(tabLayout);

    m_searchEdit = new QLineEdit;
    m_searchEdit->setPlaceholderText(QStringLiteral("搜索好友昵称/用户名"));
    mainLayout->addWidget(m_searchEdit);

    m_stack = new QStackedWidget;
    m_friendList = new QListWidget;
    m_roomList = new QListWidget;
    m_friendList->setAlternatingRowColors(true);
    m_roomList->setAlternatingRowColors(true);
    m_stack->addWidget(m_friendList);
    m_stack->addWidget(m_roomList);
    mainLayout->addWidget(m_stack, 1);

    auto *hintLabel = new QLabel(QStringLiteral("可多选，确认后统一转发"));
    hintLabel->setStyleSheet("color: gray;");
    mainLayout->addWidget(hintLabel);

    auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    buttonBox->button(QDialogButtonBox::Ok)->setText(QStringLiteral("确认转发"));
    buttonBox->button(QDialogButtonBox::Cancel)->setText(QStringLiteral("取消"));
    mainLayout->addWidget(buttonBox);

    connect(m_friendTabBtn, &QPushButton::clicked, this, &ForwardSelectDialog::switchToFriends);
    connect(m_roomTabBtn, &QPushButton::clicked, this, &ForwardSelectDialog::switchToRooms);
    connect(m_searchEdit, &QLineEdit::textChanged, this, &ForwardSelectDialog::onSearchTextChanged);
    connect(m_friendList, &QListWidget::itemChanged, this, [this](QListWidgetItem *item) {
        const QString uname = item->data(Qt::UserRole).toString();
        if (uname.isEmpty()) return;
        if (item->checkState() == Qt::Checked) m_selectedFriendUsernames.insert(uname);
        else m_selectedFriendUsernames.remove(uname);
    });
    connect(m_roomList, &QListWidget::itemChanged, this, [this](QListWidgetItem *item) {
        const int roomId = item->data(Qt::UserRole).toInt();
        if (roomId <= 0) return;
        if (item->checkState() == Qt::Checked) m_selectedRoomIds.insert(roomId);
        else m_selectedRoomIds.remove(roomId);
    });
    connect(buttonBox, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);

    setStyleSheet(
        "QDialog { background: #1f1f1f; color: #f2f2f2; }"
        "QPushButton { border: 1px solid #444; border-radius: 6px; padding: 6px 10px; }"
        "QPushButton:checked { background: #4CAF50; border-color: #4CAF50; color: white; }"
        "QLineEdit { border: 1px solid #444; border-radius: 6px; padding: 6px 8px; background: #2a2a2a; color: #f2f2f2; }"
        "QListWidget { border: 1px solid #444; border-radius: 8px; background: #262626; }"
        "QListWidget::item { padding: 8px; }"
        "QListWidget::item:selected { background: #3a3a3a; }"
    );

    switchToFriends();
}

QSet<int> ForwardSelectDialog::selectedRoomIds() const {
    return m_selectedRoomIds;
}

QSet<QString> ForwardSelectDialog::selectedFriendUsernames() const {
    return m_selectedFriendUsernames;
}

void ForwardSelectDialog::switchToFriends() {
    m_showFriends = true;
    m_searchEdit->setPlaceholderText(QStringLiteral("搜索好友昵称/用户名"));
    updateTabState();
    rebuildFriendList();
}

void ForwardSelectDialog::switchToRooms() {
    m_showFriends = false;
    m_searchEdit->setPlaceholderText(QStringLiteral("搜索房间名/ID"));
    updateTabState();
    rebuildRoomList();
}

void ForwardSelectDialog::onSearchTextChanged(const QString &) {
    if (m_showFriends) rebuildFriendList();
    else rebuildRoomList();
}

void ForwardSelectDialog::rebuildFriendList() {
    m_friendList->clear();
    const QString kw = m_searchEdit->text().trimmed().toLower();

    for (const auto &f : m_friends) {
        QString dn = f.displayName.toLower();
        QString un = f.username.toLower();
        if (!kw.isEmpty() && !dn.contains(kw) && !un.contains(kw)) continue;

        auto *item = new QListWidgetItem(buildFriendTitle(f), m_friendList);
        item->setData(Qt::UserRole, f.username);
        item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
        item->setCheckState(m_selectedFriendUsernames.contains(f.username) ? Qt::Checked : Qt::Unchecked);
    }

}

void ForwardSelectDialog::rebuildRoomList() {
    m_roomList->clear();
    const QString kw = m_searchEdit->text().trimmed().toLower();

    for (const auto &r : m_rooms) {
        QString rn = r.roomName.toLower();
        QString rid = QString::number(r.roomId);
        if (!kw.isEmpty() && !rn.contains(kw) && !rid.contains(kw)) continue;

        auto *item = new QListWidgetItem(buildRoomTitle(r), m_roomList);
        item->setData(Qt::UserRole, r.roomId);
        item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
        item->setCheckState(m_selectedRoomIds.contains(r.roomId) ? Qt::Checked : Qt::Unchecked);
    }

}

void ForwardSelectDialog::updateTabState() {
    m_friendTabBtn->setChecked(m_showFriends);
    m_roomTabBtn->setChecked(!m_showFriends);
    m_stack->setCurrentIndex(m_showFriends ? 0 : 1);
}
