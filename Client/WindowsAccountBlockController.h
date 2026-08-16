#pragma once

#include "V2WindowsAccountBlockProtocolClient.h"

#include <QObject>
#include <memory>

class V2WindowsAccountBlockViewModel;
class V2WindowsAccountBlockDirectoryViewModel;
class V2WindowsDeviceManagementTransport;

class WindowsAccountBlockController final : public QObject {
    Q_OBJECT
public:
    explicit WindowsAccountBlockController(
        V2WindowsDeviceManagementTransport *transport, QObject *parent = nullptr);
    ~WindowsAccountBlockController() override;
    V2WindowsAccountBlockViewModel *viewModel() const { return m_viewModel.get(); }
    V2WindowsAccountBlockDirectoryViewModel *directoryViewModel() const {
        return m_directoryViewModel.get();
    }

private:
    void bindSession(const QString &accountId, const QString &sessionId);
    bool submit(const QString &targetAccountId, bool blocked,
                const QString &clientOperationId);
    bool list(const QString &afterTargetAccountId);
    void receive(const QByteArray &frame);
    void disconnectSession();

    V2WindowsDeviceManagementTransport *m_transport;
    V2WindowsAccountBlockProtocolClient m_protocol;
    QString m_actorAccountId;
    std::unique_ptr<V2WindowsAccountBlockViewModel> m_viewModel;
    std::unique_ptr<V2WindowsAccountBlockDirectoryViewModel> m_directoryViewModel;
};
