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
        QString program;
        QStringList arguments;
        QString workingDirectory;
        QString readyEventName;
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

    using LaunchHandshakeFunction = std::function<PlatformResult(
        const HelperLaunch &, int)>;

    explicit WindowsUpdateHandoffApplicationService(QObject *parent = nullptr);
    explicit WindowsUpdateHandoffApplicationService(
        LaunchHandshakeFunction launchHandshake, QObject *parent = nullptr);
    ~WindowsUpdateHandoffApplicationService() override;

    bool start(const Request &request, QString *error = nullptr);
    bool isActive() const;

signals:
    void finished(const WindowsUpdateHandoffApplicationService::Result &result);

private:
    static Result execute(const Request &request,
                          const LaunchHandshakeFunction &launchHandshake);
    static PlatformResult launchAndHandshake(const HelperLaunch &launch,
                                             int timeoutMs);
    void handleFinished();

    QFutureWatcher<Result> *m_watcher = nullptr;
    LaunchHandshakeFunction m_launchHandshake;
};

Q_DECLARE_METATYPE(WindowsUpdateHandoffApplicationService::Result)
