#include "DeviceManagementDialog.h"
#include "DeviceManagementViewModel.h"

#include <QDateTime>
#include <QDialogButtonBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QLocale>
#include <QMessageBox>
#include <QPushButton>
#include <QVBoxLayout>

DeviceManagementDialog::DeviceManagementDialog(
        DeviceManagementViewModel *viewModel, QWidget *parent)
    : QDialog(parent), m_viewModel(viewModel), m_status(new QLabel(this)),
      m_devices(new QListWidget(this)), m_refresh(new QPushButton(QStringLiteral("刷新"), this)) {
    Q_ASSERT(m_viewModel);
    setWindowTitle(QStringLiteral("登录设备"));
    setMinimumSize(520, 420);
    m_status->setWordWrap(true);
    m_status->setAccessibleName(QStringLiteral("设备管理状态"));
    m_devices->setAccessibleName(QStringLiteral("登录设备列表"));
    auto *intro = new QLabel(QStringLiteral("发现陌生设备时，可撤销它的全部登录会话。"), this);
    intro->setWordWrap(true);
    auto *buttons = new QDialogButtonBox(QDialogButtonBox::Close, this);
    buttons->addButton(m_refresh, QDialogButtonBox::ActionRole);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(intro);
    layout->addWidget(m_status);
    layout->addWidget(m_devices, 1);
    layout->addWidget(buttons);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked, m_viewModel, &DeviceManagementViewModel::refresh);
    connect(m_viewModel, &DeviceManagementViewModel::changed, this, &DeviceManagementDialog::render);
    render();
}

void DeviceManagementDialog::render() {
    m_devices->clear();
    const bool available = m_viewModel->authenticated();
    m_refresh->setEnabled(available && !m_viewModel->loading()
                          && m_viewModel->revokingDeviceId().isEmpty());
    switch (m_viewModel->failure()) {
    case DeviceManagementViewModel::Failure::LoadFailed:
        m_status->setText(QStringLiteral("无法加载登录设备"));
        break;
    case DeviceManagementViewModel::Failure::RevokeFailed:
        m_status->setText(QStringLiteral("无法撤销该设备"));
        break;
    case DeviceManagementViewModel::Failure::InvalidDirectory:
        m_status->setText(QStringLiteral("登录设备数据无效"));
        break;
    case DeviceManagementViewModel::Failure::None:
        if (!available) m_status->setText(QStringLiteral("连接恢复后才能管理设备。"));
        else if (m_viewModel->loading()) m_status->setText(QStringLiteral("正在加载设备…"));
        else m_status->clear();
        break;
    }
    for (const auto &device : m_viewModel->devices()) {
        auto *item = new QListWidgetItem(m_devices);
        auto *row = new QWidget(m_devices);
        auto *layout = new QHBoxLayout(row);
        const QString platform = device.platform == DeviceManagementViewModel::Platform::Windows
                ? QStringLiteral("Windows 客户端") : QStringLiteral("Web 浏览器");
        auto *copy = new QLabel(QStringLiteral("<b>%1</b><br>%2<br><small>%3…%4</small>")
                .arg(platform,
                     device.current ? QStringLiteral("当前设备")
                                    : QStringLiteral("最近活动：%1").arg(
                                          QDateTime::fromMSecsSinceEpoch(device.lastSeenAtEpochMs)
                                                  .toString(QLocale().dateTimeFormat(
                                                          QLocale::ShortFormat))),
                     device.deviceId.left(8), device.deviceId.right(4)), row);
        layout->addWidget(copy, 1);
        if (device.current) {
            layout->addWidget(new QLabel(QStringLiteral("当前"), row));
        } else {
            auto *revoke = new QPushButton(
                    m_viewModel->revokingDeviceId() == device.deviceId
                            ? QStringLiteral("撤销中…") : QStringLiteral("撤销"), row);
            revoke->setEnabled(available && m_viewModel->revokingDeviceId().isEmpty());
            connect(revoke, &QPushButton::clicked, this,
                    [this, id = device.deviceId] { requestRevoke(id); });
            layout->addWidget(revoke);
        }
        item->setSizeHint(row->sizeHint());
        m_devices->setItemWidget(item, row);
    }
}

void DeviceManagementDialog::requestRevoke(const QString &deviceId) {
    if (QMessageBox::warning(this, QStringLiteral("撤销登录设备"),
            QStringLiteral("将立即撤销该设备的全部登录会话。是否继续？"),
            QMessageBox::Yes | QMessageBox::Cancel, QMessageBox::Cancel) == QMessageBox::Yes)
        m_viewModel->revoke(deviceId);
}
