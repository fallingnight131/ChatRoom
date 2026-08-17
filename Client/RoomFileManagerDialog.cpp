#include "RoomFileManagerDialog.h"
#include "WindowsLocaleViewModel.h"

#include <QCheckBox>
#include <QHeaderView>
#include <QHBoxLayout>
#include <QJsonObject>
#include <QLabel>
#include <QMessageBox>
#include <QPushButton>
#include <QTableWidget>
#include <QVBoxLayout>

RoomFileManagerDialog::RoomFileManagerDialog(
    QWidget *parent, WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_localeViewModel(localeViewModel) {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    resize(760, 520);

    auto *mainLayout = new QVBoxLayout(this);

    m_summaryLabel = new QLabel(this);
    mainLayout->addWidget(m_summaryLabel);

    m_table = new QTableWidget(this);
    m_table->setColumnCount(6);
    m_table->horizontalHeader()->setSectionResizeMode(1, QHeaderView::Stretch);
    m_table->horizontalHeader()->setSectionResizeMode(5, QHeaderView::ResizeToContents);
    m_table->horizontalHeader()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
    m_table->horizontalHeader()->setSectionResizeMode(2, QHeaderView::ResizeToContents);
    m_table->horizontalHeader()->setSectionResizeMode(3, QHeaderView::ResizeToContents);
    m_table->horizontalHeader()->setSectionResizeMode(4, QHeaderView::ResizeToContents);
    m_table->verticalHeader()->setVisible(false);
    m_table->setAlternatingRowColors(true);
    m_table->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_table->setSelectionBehavior(QAbstractItemView::SelectRows);
    mainLayout->addWidget(m_table);

    auto *btnLayout = new QHBoxLayout();
    btnLayout->addStretch();

    m_refreshBtn = new QPushButton(this);
    m_deleteBtn = new QPushButton(this);

    btnLayout->addWidget(m_refreshBtn);
    btnLayout->addWidget(m_deleteBtn);
    mainLayout->addLayout(btnLayout);

    connect(m_refreshBtn, &QPushButton::clicked, this, &RoomFileManagerDialog::onRefresh);
    connect(m_deleteBtn, &QPushButton::clicked, this, &RoomFileManagerDialog::onDeleteSelected);
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &RoomFileManagerDialog::applyLocale);
    }
    applyLocale();
}

void RoomFileManagerDialog::setRoomInfo(int roomId, qint64 usedFileSpace, qint64 maxFileSpace) {
    m_roomId = roomId;
    m_usedFileSpace = usedFileSpace;
    m_maxFileSpace = maxFileSpace;
    updateSummary();
}

void RoomFileManagerDialog::setFiles(const QJsonArray &files) {
    m_table->setRowCount(files.size());

    for (int row = 0; row < files.size(); ++row) {
        QJsonObject f = files.at(row).toObject();
        int fileId = f["fileId"].toInt();
        QString fileName = f["fileName"].toString();
        qint64 fileSize = static_cast<qint64>(f["fileSize"].toDouble());
        bool cleared = f["cleared"].toBool(false);
        QString createdAt = f["createdAt"].toString();

        auto *check = new QCheckBox(m_table);
        check->setEnabled(!cleared);
        check->setProperty("fileId", fileId);
        m_table->setCellWidget(row, 0, check);

        auto *nameItem = new QTableWidgetItem(fileName);
        nameItem->setData(Qt::UserRole, fileId);
        m_table->setItem(row, 1, nameItem);

        m_table->setItem(row, 2, new QTableWidgetItem(fileTypeFromName(fileName)));
        m_table->setItem(row, 3, new QTableWidgetItem(formatSize(fileSize)));
        auto *statusItem = new QTableWidgetItem;
        statusItem->setData(Qt::UserRole, cleared);
        m_table->setItem(row, 4, statusItem);
        m_table->setItem(row, 5, new QTableWidgetItem(createdAt));
    }
    updateLocalizedRows();
}

void RoomFileManagerDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.roomFileManagerTitle);
    m_summaryLabel->setAccessibleName(copy.roomFileStorageAccessible);
    m_table->setAccessibleName(copy.roomFileTableAccessible);
    m_table->setHorizontalHeaderLabels(
        {copy.roomFileSelect, copy.roomFileName, copy.roomFileType,
         copy.roomFileSize, copy.roomFileStatus, copy.roomFileUploadedAt});
    m_refreshBtn->setText(copy.refresh);
    m_refreshBtn->setAccessibleName(copy.roomFileRefreshAccessible);
    m_deleteBtn->setText(copy.roomFileDeleteSelected);
    m_deleteBtn->setAccessibleName(copy.roomFileDeleteAccessible);
    updateSummary();
    updateLocalizedRows();
}

void RoomFileManagerDialog::updateSummary() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    m_summaryLabel->setText(copy.roomFileStorageUsage.arg(
        formatSize(m_usedFileSpace), formatSize(m_maxFileSpace)));
}

void RoomFileManagerDialog::updateLocalizedRows() {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    for (int row = 0; row < m_table->rowCount(); ++row) {
        auto *nameItem = m_table->item(row, 1);
        auto *statusItem = m_table->item(row, 4);
        if (nameItem) {
            auto *typeItem = m_table->item(row, 2);
            if (typeItem) typeItem->setText(fileTypeFromName(nameItem->text()));
        }
        if (statusItem) {
            statusItem->setText(statusItem->data(Qt::UserRole).toBool()
                                    ? copy.roomFileCleared : copy.roomFileAvailable);
        }
    }
}

void RoomFileManagerDialog::onRefresh() {
    if (m_roomId > 0) {
        emit refreshRequested(m_roomId);
    }
}

void RoomFileManagerDialog::onDeleteSelected() {
    if (m_roomId <= 0) return;

    QJsonArray ids;
    for (int row = 0; row < m_table->rowCount(); ++row) {
        auto *check = qobject_cast<QCheckBox *>(m_table->cellWidget(row, 0));
        if (!check || !check->isChecked() || !check->isEnabled()) continue;
        ids.append(check->property("fileId").toInt());
    }

    if (ids.isEmpty()) {
        const auto &copy = WindowsLocaleCatalog::messages(m_locale);
        QMessageBox::information(
            this, copy.roomFileNoticeTitle, copy.roomFileSelectBeforeDelete);
        return;
    }

    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    if (QMessageBox::question(this, copy.roomFileDeleteConfirmTitle,
                              copy.roomFileDeleteConfirmPrompt)
        != QMessageBox::Yes) {
        return;
    }

    emit deleteRequested(m_roomId, ids);
}

QString RoomFileManagerDialog::formatSize(qint64 bytes) {
    if (bytes < 1024) {
        return QStringLiteral("%1 B").arg(bytes);
    }
    if (bytes < 1024 * 1024) {
        return QStringLiteral("%1 KB").arg(bytes / 1024.0, 0, 'f', 1);
    }
    if (bytes < 1024LL * 1024 * 1024) {
        return QStringLiteral("%1 MB").arg(bytes / (1024.0 * 1024.0), 0, 'f', 1);
    }
    return QStringLiteral("%1 GB").arg(bytes / (1024.0 * 1024.0 * 1024.0), 0, 'f', 2);
}

QString RoomFileManagerDialog::fileTypeFromName(const QString &fileName) const {
    const QString suffix = fileName.section('.', -1).toLower();
    static const QStringList imageExt = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList videoExt = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};

    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    if (imageExt.contains(suffix)) return copy.roomFileImageType;
    if (videoExt.contains(suffix)) return copy.roomFileVideoType;
    return copy.roomFileGenericType;
}
