#pragma once

#include "UpdatePreparationApplicationService.h"

#include <QObject>
#include <functional>

template<typename T> class QFutureWatcher;

class WindowsUpdateHandoffApplicationService : public QObject {
    Q_OBJECT
public:
    struct Request {
        UpdatePreparationApplicationService::PreparedInstaller installer;
        QString installedLauncherPath;
        QString qtCoreRuntimePath;
        QString restartExecutablePath;
        QString runRootDirectory;
        QString resultDirectory;
    };

    struct HelperLaunch {
        QString requestId;
        QString program;
        QStringList arguments;
        QString workingDirectory;
        QString readyEventName;
        QString commitEventName;
    };

    struct PlatformResult {
        bool handshaken = false;
        QString error;
    };

    struct Result {
        bool readyToQuit = false;
        QString requestId;
        QString resultFilePath;
        QString helperRunDirectory;
        QString error;
    };

    using CommitAuthorizationFunction = std::function<bool(
        const QString &, QString *)>;
    using LaunchHandshakeFunction = std::function<PlatformResult(
        const HelperLaunch &, int, const CommitAuthorizationFunction &)>;

    explicit WindowsUpdateHandoffApplicationService(QObject *parent = nullptr);
    explicit WindowsUpdateHandoffApplicationService(
        LaunchHandshakeFunction launchHandshake, QObject *parent = nullptr);
    ~WindowsUpdateHandoffApplicationService() override;

    bool start(const Request &request,
               CommitAuthorizationFunction authorizeCommit,
               QString *error = nullptr);
    bool isActive() const;

signals:
    void finished(const WindowsUpdateHandoffApplicationService::Result &result);

private:
    static Result execute(const Request &request,
                          const LaunchHandshakeFunction &launchHandshake,
                          const CommitAuthorizationFunction &authorizeCommit);
    static PlatformResult launchAndHandshake(const HelperLaunch &launch,
                                             int timeoutMs,
                                             const CommitAuthorizationFunction &authorizeCommit);
    void handleFinished();

    QFutureWatcher<Result> *m_watcher = nullptr;
    LaunchHandshakeFunction m_launchHandshake;
};

Q_DECLARE_METATYPE(WindowsUpdateHandoffApplicationService::Result)
