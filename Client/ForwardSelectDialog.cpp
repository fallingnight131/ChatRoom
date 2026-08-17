#include "ForwardSelectDialog.h"
#include "WindowsLocaleViewModel.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QLineEdit>
#include <QListWidget>
#include <QListWidgetItem>
#include <QStackedWidget>
#include <QLabel>
#include <QDialogButtonBox>
#include <QSignalBlocker>

QString ForwardSelectDialog::friendTitle(const FriendTarget &f) const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    QString name = f.displayName.trimmed().isEmpty() ? f.username : f.displayName;
    QString status = f.isOnline ? copy.online : copy.offline;
    QString title = copy.forwardFriendRow.arg(name, f.username, status);
    if (f.unread > 0) {
        title += QStringLiteral("  ") + copy.unread.arg(f.unread);
    }
    return title;
}

QString ForwardSelectDialog::roomTitle(const RoomTarget &r) const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    QString title = copy.forwardRoomRow.arg(r.roomName).arg(r.roomId);
    if (r.unread > 0) {
        title += QStringLiteral("  ") + copy.unread.arg(r.unread);
    }
    return title;
}

ForwardSelectDialog::ForwardSelectDialog(const QList<RoomTarget> &rooms,
                                         const QList<FriendTarget> &friends,
                                         QWidget *parent,
                                         WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_rooms(rooms), m_friends(friends),
      m_localeViewModel(localeViewModel)
{
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    resize(520, 520);

    auto *mainLayout = new QVBoxLayout(this);

    auto *tabLayout = new QHBoxLayout;
    m_friendTabBtn = new QPushButton;
    m_roomTabBtn = new QPushButton;
    m_friendTabBtn->setCheckable(true);
    m_roomTabBtn->setCheckable(true);
    tabLayout->addWidget(m_friendTabBtn);
    tabLayout->addWidget(m_roomTabBtn);
    mainLayout->addLayout(tabLayout);

    m_searchEdit = new QLineEdit;
    mainLayout->addWidget(m_searchEdit);

    m_stack = new QStackedWidget;
    m_friendList = new QListWidget;
    m_roomList = new QListWidget;
    m_friendList->setAlternatingRowColors(true);
    m_roomList->setAlternatingRowColors(true);
    m_stack->addWidget(m_friendList);
    m_stack->addWidget(m_roomList);
    mainLayout->addWidget(m_stack, 1);

    m_hint = new QLabel;
    m_hint->setStyleSheet("color: gray;");
    mainLayout->addWidget(m_hint);

    m_buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    mainLayout->addWidget(m_buttonBox);

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
    connect(m_buttonBox, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(m_buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &ForwardSelectDialog::applyLocale);
    }

    setStyleSheet(
        "QDialog { background: #1f1f1f; color: #f2f2f2; }"
        "QPushButton { border: 1px solid #444; border-radius: 6px; padding: 6px 10px; }"
        "QPushButton:checked { background: #4CAF50; border-color: #4CAF50; color: white; }"
        "QLineEdit { border: 1px solid #444; border-radius: 6px; padding: 6px 8px; background: #2a2a2a; color: #f2f2f2; }"
        "QListWidget { border: 1px solid #444; border-radius: 8px; background: #262626; }"
        "QListWidget::item { padding: 8px; }"
        "QListWidget::item:selected { background: #3a3a3a; }"
    );

    applyLocale();
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
    m_searchEdit->setPlaceholderText(
        WindowsLocaleCatalog::messages(m_locale).forwardSearchFriends);
    updateTabState();
    rebuildFriendList();
}

void ForwardSelectDialog::switchToRooms() {
    m_showFriends = false;
    m_searchEdit->setPlaceholderText(
        WindowsLocaleCatalog::messages(m_locale).forwardSearchRooms);
    updateTabState();
    rebuildRoomList();
}

void ForwardSelectDialog::onSearchTextChanged(const QString &) {
    if (m_showFriends) rebuildFriendList();
    else rebuildRoomList();
}

void ForwardSelectDialog::rebuildFriendList() {
    const QSignalBlocker blocker(m_friendList);
    m_friendList->clear();
    const QString kw = m_searchEdit->text().trimmed().toLower();

    for (const auto &f : m_friends) {
        QString dn = f.displayName.toLower();
        QString un = f.username.toLower();
        if (!kw.isEmpty() && !dn.contains(kw) && !un.contains(kw)) continue;

        auto *item = new QListWidgetItem(friendTitle(f), m_friendList);
        item->setData(Qt::UserRole, f.username);
        item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
        item->setCheckState(m_selectedFriendUsernames.contains(f.username) ? Qt::Checked : Qt::Unchecked);
    }

}

void ForwardSelectDialog::rebuildRoomList() {
    const QSignalBlocker blocker(m_roomList);
    m_roomList->clear();
    const QString kw = m_searchEdit->text().trimmed().toLower();

    for (const auto &r : m_rooms) {
        QString rn = r.roomName.toLower();
        QString rid = QString::number(r.roomId);
        if (!kw.isEmpty() && !rn.contains(kw) && !rid.contains(kw)) continue;

        auto *item = new QListWidgetItem(roomTitle(r), m_roomList);
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

void ForwardSelectDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.forwardTitle);
    m_friendTabBtn->setText(copy.forwardFriends);
    m_roomTabBtn->setText(copy.forwardRooms);
    m_friendList->setAccessibleName(copy.forwardFriendsAccessible);
    m_roomList->setAccessibleName(copy.forwardRoomsAccessible);
    m_hint->setText(copy.forwardHint);
    m_buttonBox->button(QDialogButtonBox::Ok)->setText(copy.forwardConfirm);
    m_buttonBox->button(QDialogButtonBox::Cancel)->setText(copy.cancel);
    m_searchEdit->setPlaceholderText(
        m_showFriends ? copy.forwardSearchFriends : copy.forwardSearchRooms);
    rebuildFriendList();
    rebuildRoomList();
}
