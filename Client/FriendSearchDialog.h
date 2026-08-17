#pragma once

#include <QDialog>
#include <QHash>
#include <QPixmap>
#include <QSet>
#include <QVector>

class QLabel;
class QLineEdit;
class QListWidget;
class QPushButton;
class WindowsLocaleViewModel;

class FriendSearchDialog final : public QDialog {
    Q_OBJECT
public:
    struct Result {
        QString username;
        QString displayName;
        bool online = false;
        bool alreadyFriend = false;
        bool currentAccount = false;
        bool avatarNeedsRefresh = false;
        QPixmap avatar;
    };

    explicit FriendSearchDialog(WindowsLocaleViewModel *localeViewModel,
                                QWidget *parent = nullptr);

    void showResults(const QVector<Result> &results);
    void showFailure(const QString &detail);
    void updateAvatar(const QString &username, const QPixmap &avatar);

signals:
    void searchRequested(const QString &keyword);
    void friendRequestRequested(const QString &username);
    void avatarRequested(const QString &username);

private:
    static constexpr int MaxResults = 100;
    enum class State { Intro, Searching, Failure, Empty, Results };
    struct RowControls {
        QLabel *metadata = nullptr;
        QLabel *presence = nullptr;
        QPushButton *request = nullptr;
    };

    void submitSearch();
    void refreshText();
    void refreshRows();

    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    QLineEdit *m_searchInput = nullptr;
    QPushButton *m_searchButton = nullptr;
    QListWidget *m_resultList = nullptr;
    QLabel *m_statusLabel = nullptr;
    QPushButton *m_closeButton = nullptr;
    State m_state = State::Intro;
    QString m_failureDetail;
    QVector<Result> m_results;
    QHash<QString, RowControls> m_rowControls;
    QHash<QString, QLabel *> m_avatarLabels;
    QSet<QString> m_requestedAccounts;
};
