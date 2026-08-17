#include "FriendSearchDialog.h"

#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QPushButton>
#include <QVBoxLayout>

#include <utility>

FriendSearchDialog::FriendSearchDialog(WindowsLocaleViewModel *localeViewModel,
                                       QWidget *parent)
    : QDialog(parent), m_localeViewModel(localeViewModel) {
    Q_ASSERT(m_localeViewModel);
    setMinimumSize(400, 350);
    resize(420, 400);

    auto *layout = new QVBoxLayout(this);
    auto *searchLayout = new QHBoxLayout;
    m_searchInput = new QLineEdit;
    m_searchInput->setObjectName(QStringLiteral("friendSearchInput"));
    m_searchButton = new QPushButton;
    m_searchButton->setObjectName(QStringLiteral("friendSearchSubmit"));
    searchLayout->addWidget(m_searchInput);
    searchLayout->addWidget(m_searchButton);
    layout->addLayout(searchLayout);

    m_resultList = new QListWidget;
    m_resultList->setObjectName(QStringLiteral("friendSearchResults"));
    m_resultList->setStyleSheet(
        QStringLiteral("QListWidget::item { padding: 4px; min-height: 40px; }"));
    layout->addWidget(m_resultList);

    m_statusLabel = new QLabel;
    m_statusLabel->setObjectName(QStringLiteral("friendSearchStatus"));
    m_statusLabel->setAlignment(Qt::AlignCenter);
    m_statusLabel->setStyleSheet(QStringLiteral("color: gray; padding: 20px;"));
    layout->addWidget(m_statusLabel);

    m_closeButton = new QPushButton;
    m_closeButton->setObjectName(QStringLiteral("friendSearchClose"));
    layout->addWidget(m_closeButton);

    connect(m_searchButton, &QPushButton::clicked,
            this, &FriendSearchDialog::submitSearch);
    connect(m_searchInput, &QLineEdit::returnPressed,
            this, &FriendSearchDialog::submitSearch);
    connect(m_closeButton, &QPushButton::clicked, this, &QDialog::accept);
    connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
            this, &FriendSearchDialog::refreshText);
    refreshText();
}

void FriendSearchDialog::submitSearch() {
    const QString keyword = m_searchInput->text().trimmed();
    if (keyword.isEmpty() || m_state == State::Searching) return;
    m_state = State::Searching;
    m_failureDetail.clear();
    m_results.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedAccounts.clear();
    m_resultList->clear();
    refreshText();
    emit searchRequested(keyword);
}

void FriendSearchDialog::showResults(const QVector<Result> &results) {
    m_results.clear();
    m_results.reserve(qMin(results.size(), MaxResults));
    QSet<QString> seenUsernames;
    for (const Result &result : results) {
        if (m_results.size() >= MaxResults) break;
        if (result.username.isEmpty() || seenUsernames.contains(result.username))
            continue;
        seenUsernames.insert(result.username);
        m_results.push_back(result);
    }
    m_state = m_results.isEmpty() ? State::Empty : State::Results;
    m_failureDetail.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedAccounts.clear();
    m_resultList->clear();

    for (const Result &result : std::as_const(m_results)) {
        auto *itemWidget = new QWidget;
        auto *row = new QHBoxLayout(itemWidget);
        row->setContentsMargins(4, 4, 4, 4);

        auto *avatar = new QLabel;
        avatar->setFixedSize(36, 36);
        avatar->setAlignment(Qt::AlignCenter);
        avatar->setPixmap(result.avatar.scaled(
            36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
        row->addWidget(avatar);
        m_avatarLabels.insert(result.username, avatar);

        auto *info = new QVBoxLayout;
        info->setSpacing(2);
        const QString identity = result.displayName.isEmpty()
            ? result.username : result.displayName;
        auto *name = new QLabel(identity);
        name->setStyleSheet(QStringLiteral("font-weight: bold; font-size: 13px;"));
        auto *metadata = new QLabel;
        metadata->setStyleSheet(QStringLiteral("color: gray; font-size: 11px;"));
        info->addWidget(name);
        info->addWidget(metadata);
        row->addLayout(info, 1);

        auto *presence = new QLabel(QStringLiteral("●"));
        presence->setStyleSheet(result.online
            ? QStringLiteral("color: #4caf50; font-size: 14px;")
            : QStringLiteral("color: #999; font-size: 14px;"));
        row->addWidget(presence);

        auto *request = new QPushButton;
        request->setObjectName(
            QStringLiteral("friendSearchRequest_%1").arg(result.username));
        request->setFixedHeight(28);
        row->addWidget(request);
        connect(request, &QPushButton::clicked, this,
                [this, username = result.username] {
            if (m_requestedAccounts.contains(username)) return;
            m_requestedAccounts.insert(username);
            refreshRows();
            emit friendRequestRequested(username);
        });

        auto *item = new QListWidgetItem;
        item->setData(Qt::UserRole, result.username);
        item->setSizeHint(QSize(0, qMax(itemWidget->sizeHint().height(), 48)));
        m_resultList->addItem(item);
        m_resultList->setItemWidget(item, itemWidget);
        m_rowControls.insert(result.username, {metadata, presence, request});
    }
    refreshText();
    for (const Result &result : std::as_const(m_results)) {
        if (result.avatarNeedsRefresh) emit avatarRequested(result.username);
    }
}

void FriendSearchDialog::showFailure(const QString &detail) {
    m_state = State::Failure;
    m_failureDetail = detail;
    m_results.clear();
    m_rowControls.clear();
    m_avatarLabels.clear();
    m_requestedAccounts.clear();
    m_resultList->clear();
    refreshText();
}

void FriendSearchDialog::updateAvatar(
        const QString &username, const QPixmap &avatar) {
    auto *label = m_avatarLabels.value(username, nullptr);
    if (!label || avatar.isNull()) return;
    label->setPixmap(avatar.scaled(
        36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    m_avatarLabels.remove(username);
}

void FriendSearchDialog::refreshText() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    setWindowTitle(copy.mainFriendSearchTitle);
    setAccessibleName(copy.mainFriendSearchAccessible);
    m_searchInput->setPlaceholderText(copy.mainFriendSearchPlaceholder);
    m_searchInput->setAccessibleName(copy.mainFriendSearchInputAccessible);
    m_searchButton->setText(m_state == State::Searching
        ? copy.mainFriendSearchSearching : copy.search);
    m_searchButton->setAccessibleName(copy.mainFriendSearchSubmitAccessible);
    m_searchButton->setEnabled(m_state != State::Searching);
    m_resultList->setAccessibleName(copy.mainFriendSearchResultsAccessible);
    m_closeButton->setText(copy.close);
    switch (m_state) {
    case State::Intro:
        m_statusLabel->setText(copy.mainFriendSearchIntro);
        m_statusLabel->show();
        break;
    case State::Searching:
        m_statusLabel->setText(copy.mainFriendSearchSearchingStatus);
        m_statusLabel->show();
        break;
    case State::Failure:
        m_statusLabel->setText(m_failureDetail.isEmpty()
            ? copy.mainFriendSearchFailed : m_failureDetail);
        m_statusLabel->show();
        break;
    case State::Empty:
        m_statusLabel->setText(copy.mainFriendSearchEmpty);
        m_statusLabel->show();
        break;
    case State::Results:
        m_statusLabel->hide();
        break;
    }
    refreshRows();
}

void FriendSearchDialog::refreshRows() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    for (const Result &result : std::as_const(m_results)) {
        const RowControls controls = m_rowControls.value(result.username);
        if (!controls.metadata || !controls.presence || !controls.request) continue;
        const QString identity = result.displayName.isEmpty()
            ? result.username : result.displayName;
        controls.metadata->setText(copy.mainFriendSearchMetadata.arg(result.username));
        controls.presence->setAccessibleName(result.online
            ? copy.mainFriendSearchOnlineAccessible.arg(identity)
            : copy.mainFriendSearchOfflineAccessible.arg(identity));
        const bool requested = m_requestedAccounts.contains(result.username);
        if (result.currentAccount) {
            controls.request->setText(copy.mainFriendSearchCurrentAccount);
            controls.request->setAccessibleName(
                copy.mainFriendSearchCurrentAccountAccessible.arg(identity));
        } else if (result.alreadyFriend) {
            controls.request->setText(copy.mainFriendSearchAlreadyFriend);
            controls.request->setAccessibleName(
                copy.mainFriendSearchAlreadyFriendAccessible.arg(identity));
        } else if (requested) {
            controls.request->setText(copy.mainFriendSearchSent);
            controls.request->setAccessibleName(
                copy.mainFriendSearchSentAccessible.arg(identity));
        } else {
            controls.request->setText(copy.mainFriendSearchSendRequest);
            controls.request->setAccessibleName(
                copy.mainFriendSearchSendRequestAccessible.arg(identity));
        }
        controls.request->setEnabled(
            !result.currentAccount && !result.alreadyFriend && !requested);
        for (int index = 0; index < m_resultList->count(); ++index) {
            auto *item = m_resultList->item(index);
            if (item->data(Qt::UserRole).toString() != result.username) continue;
            item->setData(Qt::AccessibleTextRole,
                (result.online
                    ? copy.mainFriendSearchResultOnlineAccessible
                    : copy.mainFriendSearchResultOfflineAccessible)
                    .arg(identity, result.username));
            break;
        }
    }
}
