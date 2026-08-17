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

class RoomSearchDialog final : public QDialog {
    Q_OBJECT
public:
    struct Result {
        int roomId = 0;
        QString roomName;
        int memberCount = 0;
        bool alreadyJoined = false;
        bool avatarNeedsRefresh = false;
        QPixmap avatar;
    };

    explicit RoomSearchDialog(WindowsLocaleViewModel *localeViewModel,
                              QWidget *parent = nullptr);

    void showResults(const QVector<Result> &results);
    void showFailure(const QString &detail);
    void updateRoomAvatar(int roomId, const QPixmap &avatar);

signals:
    void searchRequested(const QString &keyword);
    void joinRequested(int roomId);
    void roomAvatarRequested(int roomId);

private:
    enum class State { Intro, Searching, Failure, Empty, Results };
    struct RowControls {
        QLabel *metadata = nullptr;
        QPushButton *join = nullptr;
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
    QHash<int, RowControls> m_rowControls;
    QHash<int, QLabel *> m_avatarLabels;
    QSet<int> m_requestedJoins;
};
