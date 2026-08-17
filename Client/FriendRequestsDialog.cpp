#include "FriendRequestsDialog.h"

#include "WindowsLocaleCatalog.h"
#include "WindowsLocaleViewModel.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QSet>
#include <QVBoxLayout>

#include <utility>

FriendRequestsDialog::FriendRequestsDialog(
        WindowsLocaleViewModel *localeViewModel, QWidget *parent)
    : QDialog(parent), m_localeViewModel(localeViewModel) {
    Q_ASSERT(m_localeViewModel);
    setMinimumSize(400, 350);
    resize(420, 400);
    auto *layout = new QVBoxLayout(this);
    m_titleLabel = new QLabel;
    m_titleLabel->setObjectName(QStringLiteral("friendRequestsTitle"));
    m_titleLabel->setStyleSheet(
        QStringLiteral("font-size: 14px; font-weight: bold; padding: 4px;"));
    layout->addWidget(m_titleLabel);
    m_requestList = new QListWidget;
    m_requestList->setObjectName(QStringLiteral("friendRequestsList"));
    m_requestList->setStyleSheet(
        QStringLiteral("QListWidget::item { padding: 4px; min-height: 40px; }"));
    layout->addWidget(m_requestList);
    m_statusLabel = new QLabel;
    m_statusLabel->setObjectName(QStringLiteral("friendRequestsStatus"));
    m_statusLabel->setAlignment(Qt::AlignCenter);
    layout->addWidget(m_statusLabel);
    m_closeButton = new QPushButton;
    m_closeButton->setObjectName(QStringLiteral("friendRequestsClose"));
    layout->addWidget(m_closeButton);
    connect(m_closeButton, &QPushButton::clicked, this, &QDialog::accept);
    connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
            this, &FriendRequestsDialog::refreshText);
    refreshText();
}

void FriendRequestsDialog::setRequests(const QVector<Request> &requests) {
    m_requests.clear();
    m_requests.reserve(qMin(requests.size(), MaxRequests));
    QSet<int> seenIds;
    QSet<QString> seenUsernames;
    for (const Request &request : requests) {
        if (m_requests.size() >= MaxRequests) break;
        if (request.requestId <= 0 || request.username.isEmpty()
                || seenIds.contains(request.requestId)
                || seenUsernames.contains(request.username)) continue;
        seenIds.insert(request.requestId);
        seenUsernames.insert(request.username);
        m_requests.push_back(request);
    }
    m_pendingRequestId = 0;
    m_pendingOperation = Operation::None;
    m_operationFailed = false;
    m_failureDetail.clear();
    m_rowControls.clear();
    m_rowStates.clear();
    m_avatarLabels.clear();
    m_requestList->clear();

    for (const Request &request : std::as_const(m_requests)) {
        auto *itemWidget = new QWidget;
        auto *row = new QHBoxLayout(itemWidget);
        row->setContentsMargins(4, 4, 4, 4);
        auto *avatar = new QLabel;
        avatar->setFixedSize(36, 36);
        avatar->setAlignment(Qt::AlignCenter);
        avatar->setPixmap(request.avatar.scaled(
            36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
        row->addWidget(avatar);
        m_avatarLabels.insert(request.username, avatar);

        const QString identity = request.displayName.isEmpty()
            ? request.username : request.displayName;
        auto *info = new QVBoxLayout;
        info->setSpacing(2);
        auto *name = new QLabel(identity);
        name->setStyleSheet(QStringLiteral("font-weight: bold; font-size: 13px;"));
        auto *metadata = new QLabel;
        metadata->setObjectName(
            QStringLiteral("friendRequestMetadata_%1").arg(request.requestId));
        metadata->setStyleSheet(QStringLiteral("color: gray; font-size: 11px;"));
        info->addWidget(name);
        info->addWidget(metadata);
        row->addLayout(info, 1);

        auto *accept = new QPushButton;
        accept->setObjectName(
            QStringLiteral("friendRequestAccept_%1").arg(request.requestId));
        auto *reject = new QPushButton;
        reject->setObjectName(
            QStringLiteral("friendRequestReject_%1").arg(request.requestId));
        accept->setFixedHeight(28);
        reject->setFixedHeight(28);
        row->addWidget(accept);
        row->addWidget(reject);
        connect(accept, &QPushButton::clicked, this,
                [this, requestId = request.requestId,
                 username = request.username] {
            startAccept(requestId, username);
        });
        connect(reject, &QPushButton::clicked, this,
                [this, requestId = request.requestId] { startReject(requestId); });

        auto *item = new QListWidgetItem;
        item->setData(Qt::UserRole, request.requestId);
        item->setData(Qt::UserRole + 1, request.username);
        item->setSizeHint(QSize(0, qMax(itemWidget->sizeHint().height(), 48)));
        m_requestList->addItem(item);
        m_requestList->setItemWidget(item, itemWidget);
        m_rowControls.insert(request.requestId, {metadata, accept, reject});
        m_rowStates.insert(request.requestId, RowState::Open);
    }
    refreshText();
    for (const Request &request : std::as_const(m_requests)) {
        if (request.avatarNeedsRefresh) emit avatarRequested(request.username);
    }
}

void FriendRequestsDialog::startAccept(
        int requestId, const QString &username) {
    if (m_pendingOperation != Operation::None
            || m_rowStates.value(requestId, RowState::Accepted) != RowState::Open)
        return;
    m_pendingRequestId = requestId;
    m_pendingOperation = Operation::Accept;
    m_operationFailed = false;
    m_failureDetail.clear();
    refreshText();
    emit acceptRequested(requestId, username);
}

void FriendRequestsDialog::startReject(int requestId) {
    if (m_pendingOperation != Operation::None
            || m_rowStates.value(requestId, RowState::Accepted) != RowState::Open)
        return;
    m_pendingRequestId = requestId;
    m_pendingOperation = Operation::Reject;
    m_operationFailed = false;
    m_failureDetail.clear();
    refreshText();
    emit rejectRequested(requestId);
}

void FriendRequestsDialog::resolveAccept(bool success, const QString &detail) {
    resolve(Operation::Accept, success, detail);
}

void FriendRequestsDialog::resolveReject(bool success, const QString &detail) {
    resolve(Operation::Reject, success, detail);
}

void FriendRequestsDialog::resolve(
        Operation operation, bool success, const QString &detail) {
    if (m_pendingOperation != operation || m_pendingRequestId <= 0) return;
    if (success) {
        m_rowStates[m_pendingRequestId] = operation == Operation::Accept
            ? RowState::Accepted : RowState::Rejected;
        m_operationFailed = false;
        m_failureDetail.clear();
    } else {
        m_operationFailed = true;
        m_failureDetail = detail;
    }
    m_pendingRequestId = 0;
    m_pendingOperation = Operation::None;
    refreshText();
}

void FriendRequestsDialog::updateAvatar(
        const QString &username, const QPixmap &avatar) {
    auto *label = m_avatarLabels.value(username, nullptr);
    if (!label || avatar.isNull()) return;
    label->setPixmap(avatar.scaled(
        36, 36, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    m_avatarLabels.remove(username);
}

void FriendRequestsDialog::refreshText() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    setWindowTitle(copy.mainFriendRequestsTitle);
    setAccessibleName(copy.mainFriendRequestsAccessible);
    m_titleLabel->setText(copy.mainFriendRequestsCount.arg(m_requests.size()));
    m_requestList->setAccessibleName(copy.mainFriendRequestsListAccessible);
    m_closeButton->setText(copy.close);
    if (m_requests.isEmpty()) {
        m_statusLabel->setText(copy.mainFriendRequestsEmpty);
        m_statusLabel->show();
    } else if (m_operationFailed) {
        m_statusLabel->setText(m_failureDetail.isEmpty()
            ? copy.mainFriendRequestsFailed : m_failureDetail);
        m_statusLabel->show();
    } else if (m_pendingOperation != Operation::None) {
        m_statusLabel->setText(copy.mainFriendRequestsPending);
        m_statusLabel->show();
    } else {
        m_statusLabel->hide();
    }
    refreshRows();
}

void FriendRequestsDialog::refreshRows() {
    const auto &copy = WindowsLocaleCatalog::messages(m_localeViewModel->locale());
    const bool operationPending = m_pendingOperation != Operation::None;
    for (const Request &request : std::as_const(m_requests)) {
        const RowControls controls = m_rowControls.value(request.requestId);
        if (!controls.metadata || !controls.accept || !controls.reject) continue;
        const QString identity = request.displayName.isEmpty()
            ? request.username : request.displayName;
        const RowState state = m_rowStates.value(request.requestId, RowState::Open);
        controls.accept->setText(state == RowState::Accepted
            ? copy.mainFriendRequestsAccepted : copy.mainFriendRequestsAccept);
        controls.reject->setText(state == RowState::Rejected
            ? copy.mainFriendRequestsRejected : copy.mainFriendRequestsReject);
        controls.accept->setEnabled(state == RowState::Open && !operationPending);
        controls.reject->setEnabled(state == RowState::Open && !operationPending);
        controls.accept->setAccessibleName(state == RowState::Accepted
            ? copy.mainFriendRequestsAcceptedAccessible.arg(identity)
            : copy.mainFriendRequestsAcceptAccessible.arg(identity));
        controls.reject->setAccessibleName(state == RowState::Rejected
            ? copy.mainFriendRequestsRejectedAccessible.arg(identity)
            : copy.mainFriendRequestsRejectAccessible.arg(identity));
        controls.metadata->setText(
            copy.mainFriendRequestsMetadata.arg(request.username));
        for (int index = 0; index < m_requestList->count(); ++index) {
            auto *item = m_requestList->item(index);
            if (item->data(Qt::UserRole).toInt() != request.requestId) continue;
            item->setData(Qt::AccessibleTextRole,
                copy.mainFriendRequestsRowAccessible.arg(identity, request.username));
            break;
        }
    }
}
