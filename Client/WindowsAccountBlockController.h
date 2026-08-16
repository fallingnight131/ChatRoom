#pragma once

#include "V2WindowsAccountBlockProtocolClient.h"

#include <QObject>
#include <memory>

class V2WindowsAccountBlockViewModel;
class V2WindowsDeviceManagementTransport;

class WindowsAccountBlockController final : public QObject {
    Q_OBJECT
public:
    explicit WindowsAccountBlockController(
        V2WindowsDeviceManagementTransport *transport, QObject *parent = nullptr);
    ~WindowsAccountBlockController() override;
    V2WindowsAccountBlockViewModel *viewModel() const { return m_viewModel.get(); }

private:
    void bindSession(const QString &accountId, const QString &sessionId);
    bool submit(const QString &targetAccountId, bool blocked,
                const QString &clientOperationId);
    void receive(const QByteArray &frame);
    void disconnectSession();

    V2WindowsDeviceManagementTransport *m_transport;
    V2WindowsAccountBlockProtocolClient m_protocol;
    std::unique_ptr<V2WindowsAccountBlockViewModel> m_viewModel;
};
