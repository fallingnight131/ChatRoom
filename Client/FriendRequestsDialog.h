#pragma once

#include <QDialog>
#include <QHash>
#include <QPixmap>
#include <QVector>

class QLabel;
class QListWidget;
class QPushButton;
class WindowsLocaleViewModel;

class FriendRequestsDialog final : public QDialog {
    Q_OBJECT
public:
    struct Request {
        int requestId = 0;
        QString username;
        QString displayName;
        bool avatarNeedsRefresh = false;
        QPixmap avatar;
    };

    explicit FriendRequestsDialog(WindowsLocaleViewModel *localeViewModel,
                                  QWidget *parent = nullptr);

    void setRequests(const QVector<Request> &requests);
    void resolveAccept(bool success, const QString &detail);
    void resolveReject(bool success, const QString &detail);
    void updateAvatar(const QString &username, const QPixmap &avatar);

signals:
    void acceptRequested(int requestId, const QString &username);
    void rejectRequested(int requestId);
    void avatarRequested(const QString &username);

private:
    static constexpr int MaxRequests = 100;
    enum class Operation { None, Accept, Reject };
    enum class RowState { Open, Accepted, Rejected };
    struct RowControls {
        QLabel *metadata = nullptr;
        QPushButton *accept = nullptr;
        QPushButton *reject = nullptr;
    };

    void startAccept(int requestId, const QString &username);
    void startReject(int requestId);
    void resolve(Operation operation, bool success, const QString &detail);
    void refreshText();
    void refreshRows();

    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    QLabel *m_titleLabel = nullptr;
    QListWidget *m_requestList = nullptr;
    QLabel *m_statusLabel = nullptr;
    QPushButton *m_closeButton = nullptr;
    QVector<Request> m_requests;
    QHash<int, RowControls> m_rowControls;
    QHash<int, RowState> m_rowStates;
    QHash<QString, QLabel *> m_avatarLabels;
    int m_pendingRequestId = 0;
    Operation m_pendingOperation = Operation::None;
    bool m_operationFailed = false;
    QString m_failureDetail;
};
