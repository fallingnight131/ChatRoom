#include "DeviceManagementDialog.h"
#include "DeviceManagementViewModel.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
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
        DeviceManagementViewModel *viewModel, QWidget *parent,
        WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_viewModel(viewModel), m_status(new QLabel(this)),
      m_intro(new QLabel(this)), m_devices(new QListWidget(this)),
      m_refresh(new QPushButton(this)), m_close(new QPushButton(this)),
      m_localeViewModel(localeViewModel) {
    Q_ASSERT(m_viewModel);
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    setMinimumSize(520, 420);
    m_status->setWordWrap(true);
    m_intro->setWordWrap(true);
    auto *buttons = new QDialogButtonBox(this);
    buttons->addButton(m_close, QDialogButtonBox::RejectRole);
    buttons->addButton(m_refresh, QDialogButtonBox::ActionRole);
    auto *layout = new QVBoxLayout(this);
    layout->addWidget(m_intro);
    layout->addWidget(m_status);
    layout->addWidget(m_devices, 1);
    layout->addWidget(buttons);
    connect(m_close, &QPushButton::clicked, this, &QDialog::reject);
    connect(m_refresh, &QPushButton::clicked, m_viewModel, &DeviceManagementViewModel::refresh);
    connect(m_viewModel, &DeviceManagementViewModel::changed, this, &DeviceManagementDialog::render);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &DeviceManagementDialog::applyLocale);
    }
    applyLocale();
}

void DeviceManagementDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.deviceManagementTitle);
    m_intro->setText(copy.deviceManagementIntro);
    m_status->setAccessibleName(copy.deviceManagementStatusAccessible);
    m_devices->setAccessibleName(copy.deviceManagementListAccessible);
    m_refresh->setText(copy.refresh);
    m_refresh->setAccessibleName(copy.deviceManagementRefreshAccessible);
    m_close->setText(copy.deviceManagementClose);
    render();
}

QString DeviceManagementDialog::failureText() const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    switch (m_viewModel->failure()) {
    case DeviceManagementViewModel::Failure::LoadFailed:
        return copy.deviceManagementLoadFailed;
    case DeviceManagementViewModel::Failure::RevokeFailed:
        return copy.deviceManagementRevokeFailed;
    case DeviceManagementViewModel::Failure::InvalidDirectory:
        return copy.deviceManagementInvalidDirectory;
    case DeviceManagementViewModel::Failure::None:
        return {};
    }
    return {};
}

void DeviceManagementDialog::render() {
    const QString selectedDeviceId = m_devices->currentItem()
        ? m_devices->currentItem()->data(Qt::UserRole).toString() : QString();
    QString focusedDeviceId;
    QWidget *focused = QApplication::focusWidget();
    for (int rowIndex = 0; focused && rowIndex < m_devices->count(); ++rowIndex) {
        QWidget *row = m_devices->itemWidget(m_devices->item(rowIndex));
        if (row && (row == focused || row->isAncestorOf(focused))) {
            focusedDeviceId = m_devices->item(rowIndex)->data(Qt::UserRole).toString();
            break;
        }
    }
    m_devices->clear();
    const auto &catalog = WindowsLocaleCatalog::messages(m_locale);
    const bool available = m_viewModel->authenticated();
    m_refresh->setEnabled(available && !m_viewModel->loading()
                          && m_viewModel->revokingDeviceId().isEmpty());
    const QString failure = failureText();
    if (!failure.isEmpty()) m_status->setText(failure);
    else if (!available) m_status->setText(catalog.deviceManagementDisconnected);
    else if (m_viewModel->loading()) m_status->setText(catalog.deviceManagementLoading);
    else m_status->clear();
    const QLocale dateLocale(m_locale == WindowsLocale::EnUs
                                 ? QStringLiteral("en_US") : QStringLiteral("zh_CN"));
    for (const auto &device : m_viewModel->devices()) {
        auto *item = new QListWidgetItem(m_devices);
        item->setData(Qt::UserRole, device.deviceId);
        auto *row = new QWidget(m_devices);
        auto *layout = new QHBoxLayout(row);
        const QString platform = device.platform == DeviceManagementViewModel::Platform::Windows
                ? catalog.deviceManagementWindowsClient
                : catalog.deviceManagementWebBrowser;
        const QString activity = device.current ? catalog.deviceManagementCurrentDevice
                : catalog.deviceManagementRecentActivity.arg(
                    QDateTime::fromMSecsSinceEpoch(device.lastSeenAtEpochMs)
                        .toString(dateLocale.dateTimeFormat(QLocale::ShortFormat)));
        const QString rowText = QStringLiteral("%1\n%2\n%3…%4")
                .arg(platform,
                     activity, device.deviceId.left(8), device.deviceId.right(4));
        auto *description = new QLabel(rowText, row);
        description->setTextFormat(Qt::PlainText);
        description->setAccessibleName(rowText);
        row->setAccessibleName(rowText);
        layout->addWidget(description, 1);
        QPushButton *revokeButton = nullptr;
        if (device.current) {
            layout->addWidget(new QLabel(catalog.deviceManagementCurrent, row));
        } else {
            revokeButton = new QPushButton(
                    m_viewModel->revokingDeviceId() == device.deviceId
                            ? catalog.deviceManagementRevoking
                            : catalog.deviceManagementRevoke, row);
            revokeButton->setEnabled(available && m_viewModel->revokingDeviceId().isEmpty());
            connect(revokeButton, &QPushButton::clicked, this,
                    [this, id = device.deviceId] { requestRevoke(id); });
            layout->addWidget(revokeButton);
        }
        item->setSizeHint(row->sizeHint());
        m_devices->setItemWidget(item, row);
        if (device.deviceId == selectedDeviceId) m_devices->setCurrentItem(item);
        if (device.deviceId == focusedDeviceId) {
            if (revokeButton) revokeButton->setFocus();
            else m_devices->setFocus();
        }
    }
}

void DeviceManagementDialog::requestRevoke(const QString &deviceId) {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    QMessageBox confirmation(
        QMessageBox::Warning, copy.deviceManagementRevokeTitle,
        copy.deviceManagementRevokePrompt, QMessageBox::NoButton, this);
    auto *cancel = confirmation.addButton(copy.cancel, QMessageBox::RejectRole);
    auto *confirm = confirmation.addButton(
        copy.deviceManagementRevoke, QMessageBox::AcceptRole);
    confirmation.setDefaultButton(cancel);
    confirmation.exec();
    if (confirmation.clickedButton() == confirm) m_viewModel->revoke(deviceId);
}
