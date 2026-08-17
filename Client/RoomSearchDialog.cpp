#include "RoomSearchDialog.h"

#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QPushButton>
#include <QVBoxLayout>

RoomSearchDialog::RoomSearchDialog(WindowsLocaleViewModel *localeViewModel,
                                   QWidget *parent)
    : QDialog(parent), m_localeViewModel(localeViewModel) {
    Q_ASSERT(m_localeViewModel);
    setMinimumSize(400, 350);
    resize(420, 400);

    auto *layout = new QVBoxLayout(this);
    auto *searchLayout = new QHBoxLayout;
    m_searchInput = new QLineEdit;
    m_searchInput->setObjectName(QStringLiteral("roomSearchInput"));
    m_searchButton = new QPushButton;
    m_searchButton->setObjectName(QStringLiteral("roomSearchSubmit"));
    searchLayout->addWidget(m_searchInput);
    searchLayout->addWidget(m_searchButton);
    layout->addLayout(searchLayout);

    m_resultList = new QListWidget;
    m_resultList->setObjectName(QStringLiteral("roomSearchResults"));
    m_resultList->setStyleSheet(
        QStringLiteral("QListWidget::item { padding: 4px; min-height: 40px; }"));
    layout->addWidget(m_resultList);

    m_statusLabel = new QLabel;
    m_statusLabel->setObjectName(QStringLiteral("roomSearchStatus"));
    m_statusLabel->setAlignment(Qt::AlignCenter);
    m_statusLabel->setStyleSheet(QStringLiteral("color: gray; padding: 20px;"));
    layout->addWidget(m_statusLabel);

    m_closeButton = new QPushButton;
    m_closeButton->setObjectName(QStringLiteral("roomSearchClose"));
    layout->addWidget(m_closeButton);

    connect(m_searchButton, &QPushButton::clicked,
            this, &RoomSearchDialog::submitSearch);
    connect(m_searchInput, &QLineEdit::returnPressed,
            this, &RoomSearchDialog::submitSearch);
    connect(m_closeButton, &QPushButton::clicked, this, &QDialog::accept);
    connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
            this, &RoomSearchDialog::refreshText);
    refreshText();
}

void RoomSearchDialog::submitSearch() {
    const QString keyword = m_searchInput->text().trimmed();
    if (keyword.isEmpty() || m_state == State::Searching) return;
    m_state = State::Searching;
    m_failureDetail.clear();
    m_results.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedJoins.clear();
    m_resultList->clear();
    refreshText();
    emit searchRequested(keyword);
}

void RoomSearchDialog::showResults(const QVector<Result> &results) {
    m_results = results;
    m_state = results.isEmpty() ? State::Empty : State::Results;
    m_failureDetail.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedJoins.clear();
    m_resultList->clear();

    for (const Result &result : m_results) {
        auto *itemWidget = new QWidget;
        auto *row = new QHBoxLayout(itemWidget);
        row->setContentsMargins(4, 4, 4, 4);

        auto *avatar = new QLabel;
        avatar->setFixedSize(36, 36);
        avatar->setAlignment(Qt::AlignCenter);
        avatar->setPixmap(result.avatar.scaled(
            36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
        row->addWidget(avatar);
        m_avatarLabels.insert(result.roomId, avatar);

        auto *info = new QVBoxLayout;
        info->setSpacing(2);
        auto *name = new QLabel(result.roomName);
        name->setStyleSheet(QStringLiteral("font-weight: bold; font-size: 13px;"));
        auto *metadata = new QLabel;
        metadata->setStyleSheet(QStringLiteral("color: gray; font-size: 11px;"));
        info->addWidget(name);
        info->addWidget(metadata);
        row->addLayout(info, 1);

        auto *join = new QPushButton;
        join->setObjectName(QStringLiteral("roomSearchJoin_%1").arg(result.roomId));
        join->setFixedHeight(28);
        row->addWidget(join);
        connect(join, &QPushButton::clicked, this, [this, roomId = result.roomId] {
            if (m_requestedJoins.contains(roomId)) return;
            m_requestedJoins.insert(roomId);
            refreshRows();
            emit joinRequested(roomId);
        });

        auto *item = new QListWidgetItem;
        item->setData(Qt::UserRole, result.roomId);
        item->setSizeHint(QSize(0, qMax(itemWidget->sizeHint().height(), 48)));
        m_resultList->addItem(item);
        m_resultList->setItemWidget(item, itemWidget);
        m_rowControls.insert(result.roomId, {metadata, join});
    }
    refreshText();
    for (const Result &result : m_results) {
        if (result.avatarNeedsRefresh) emit roomAvatarRequested(result.roomId);
    }
}

void RoomSearchDialog::showFailure(const QString &detail) {
    m_state = State::Failure;
    m_failureDetail = detail;
    m_results.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedJoins.clear();
    m_resultList->clear();
    refreshText();
}

void RoomSearchDialog::updateRoomAvatar(int roomId, const QPixmap &avatar) {
    auto *label = m_avatarLabels.value(roomId, nullptr);
    if (!label || avatar.isNull()) return;
    label->setPixmap(avatar.scaled(
        36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    m_avatarLabels.remove(roomId);
}

void RoomSearchDialog::refreshText() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    setWindowTitle(copy.mainRoomSearchTitle);
    setAccessibleName(copy.mainRoomSearchAccessible);
    m_searchInput->setPlaceholderText(copy.mainRoomSearchPlaceholder);
    m_searchInput->setAccessibleName(copy.mainRoomSearchInputAccessible);
    m_searchButton->setText(m_state == State::Searching
        ? copy.mainRoomSearchSearching : copy.search);
    m_searchButton->setAccessibleName(copy.mainRoomSearchSubmitAccessible);
    m_searchButton->setEnabled(m_state != State::Searching);
    m_resultList->setAccessibleName(copy.mainRoomSearchResultsAccessible);
    m_closeButton->setText(copy.close);

    switch (m_state) {
    case State::Intro:
        m_statusLabel->setText(copy.mainRoomSearchIntro);
        m_statusLabel->show();
        break;
    case State::Searching:
        m_statusLabel->setText(copy.mainRoomSearchSearchingStatus);
        m_statusLabel->show();
        break;
    case State::Failure:
        m_statusLabel->setText(m_failureDetail.isEmpty()
            ? copy.mainRoomSearchFailed : m_failureDetail);
        m_statusLabel->show();
        break;
    case State::Empty:
        m_statusLabel->setText(copy.mainRoomSearchEmpty);
        m_statusLabel->show();
        break;
    case State::Results:
        m_statusLabel->hide();
        break;
    }
    refreshRows();
}

void RoomSearchDialog::refreshRows() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    for (const Result &result : m_results) {
        const RowControls controls = m_rowControls.value(result.roomId);
        if (!controls.metadata || !controls.join) continue;
        controls.metadata->setText(copy.mainRoomSearchMetadata
            .arg(result.roomId).arg(result.memberCount));
        const bool requested = m_requestedJoins.contains(result.roomId);
        controls.join->setText(result.alreadyJoined
            ? copy.mainRoomSearchJoined
            : requested ? copy.mainRoomSearchRequested : copy.mainRoomSearchJoin);
        controls.join->setEnabled(!result.alreadyJoined && !requested);
        controls.join->setAccessibleName(result.alreadyJoined
            ? copy.mainRoomSearchJoinedAccessible.arg(result.roomName)
            : requested ? copy.mainRoomSearchRequestedAccessible.arg(result.roomName)
                        : copy.mainRoomSearchJoinAccessible.arg(result.roomName));
        for (int index = 0; index < m_resultList->count(); ++index) {
            auto *item = m_resultList->item(index);
            if (item->data(Qt::UserRole).toInt() != result.roomId) continue;
            item->setData(Qt::AccessibleTextRole,
                copy.mainRoomSearchResultAccessible
                    .arg(result.roomName,
                         QString::number(result.roomId),
                         QString::number(result.memberCount)));
            break;
        }
    }
}
