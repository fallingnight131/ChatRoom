#include "WindowsUpdateInstallCoordinator.h"

#include <QRegularExpression>

#include <utility>

namespace {
const QRegularExpression Version(QStringLiteral(
    R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$)"));
}

WindowsUpdateInstallCoordinator::WindowsUpdateInstallCoordinator(
        QString lifecycleStateDirectory, QString resultDirectory,
        QString runRootDirectory, QObject *parent)
    : QObject(parent),
      m_lifecycle(std::move(lifecycleStateDirectory), resultDirectory,
                  runRootDirectory),
      m_handoff(new WindowsUpdateHandoffApplicationService(this)) {
    connect(m_handoff, &WindowsUpdateHandoffApplicationService::finished,
            this, &WindowsUpdateInstallCoordinator::handleHandoff);
}

WindowsUpdateInstallCoordinator::WindowsUpdateInstallCoordinator(
        QString lifecycleStateDirectory, QString resultDirectory,
        QString runRootDirectory,
        WindowsUpdateHandoffApplicationService::LaunchHandshakeFunction handshake,
        QObject *parent)
    : QObject(parent),
      m_lifecycle(std::move(lifecycleStateDirectory), resultDirectory,
                  runRootDirectory),
      m_handoff(new WindowsUpdateHandoffApplicationService(
          std::move(handshake), this)) {
    connect(m_handoff, &WindowsUpdateHandoffApplicationService::finished,
            this, &WindowsUpdateInstallCoordinator::handleHandoff);
}

bool WindowsUpdateInstallCoordinator::start(
        const Request &request, QString *error) {
    if (error) error->clear();
    if (isActive()) {
        if (error) *error = QStringLiteral("Windows update install is already active");
        return false;
    }
    if (!Version.match(request.targetVersion).hasMatch()
            || !request.createdAtUtc.isValid()
            || request.createdAtUtc.timeSpec() != Qt::UTC) {
        if (error) *error = QStringLiteral("Windows update install request is invalid");
        return false;
    }
    m_request = request;
    if (!m_handoff->start(request.handoff, error)) {
        m_request = {};
        return false;
    }
    return true;
}

bool WindowsUpdateInstallCoordinator::isActive() const {
    return m_handoff && m_handoff->isActive();
}

void WindowsUpdateInstallCoordinator::handleHandoff(
        const WindowsUpdateHandoffApplicationService::Result &handoff) {
    Result result;
    result.requestId = handoff.requestId;
    result.error = handoff.error;
    if (handoff.readyToQuit) {
        QString persistenceError;
        const bool recorded = m_lifecycle.recordPending(
            {handoff.requestId, m_request.targetVersion, m_request.createdAtUtc},
            &persistenceError);
        if (recorded) {
            result.quitAuthorized = true;
            result.error.clear();
        } else {
            result.error = persistenceError.isEmpty()
                ? QStringLiteral("pending update could not be persisted")
                : persistenceError;
        }
    }
    m_request = {};
    emit finished(result);
}
