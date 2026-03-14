#include "RoomFileManagerDialog.h"

#include <QCheckBox>
#include <QHeaderView>
#include <QHBoxLayout>
#include <QJsonObject>
#include <QLabel>
#include <QMessageBox>
#include <QPushButton>
#include <QTableWidget>
#include <QVBoxLayout>

RoomFileManagerDialog::RoomFileManagerDialog(QWidget *parent)
    : QDialog(parent) {
    setWindowTitle(QStringLiteral("文件管理"));
    resize(760, 520);

    auto *mainLayout = new QVBoxLayout(this);

    m_summaryLabel = new QLabel(this);
    m_summaryLabel->setText(QStringLiteral("当前文件空间: 0 B / 0 B"));
    mainLayout->addWidget(m_summaryLabel);

    m_table = new QTableWidget(this);
    m_table->setColumnCount(6);
    m_table->setHorizontalHeaderLabels(
        {QStringLiteral("选择"), QStringLiteral("文件名"), QStringLiteral("类型"),
         QStringLiteral("大小"), QStringLiteral("状态"), QStringLiteral("上传时间")});
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

    m_refreshBtn = new QPushButton(QStringLiteral("刷新"), this);
    m_deleteBtn = new QPushButton(QStringLiteral("删除所选文件"), this);

    btnLayout->addWidget(m_refreshBtn);
    btnLayout->addWidget(m_deleteBtn);
    mainLayout->addLayout(btnLayout);

    connect(m_refreshBtn, &QPushButton::clicked, this, &RoomFileManagerDialog::onRefresh);
    connect(m_deleteBtn, &QPushButton::clicked, this, &RoomFileManagerDialog::onDeleteSelected);
}

void RoomFileManagerDialog::setRoomInfo(int roomId, qint64 usedFileSpace, qint64 maxFileSpace) {
    m_roomId = roomId;
    m_usedFileSpace = usedFileSpace;
    m_maxFileSpace = maxFileSpace;
    m_summaryLabel->setText(
        QStringLiteral("当前文件空间: %1 / %2")
            .arg(formatSize(m_usedFileSpace), formatSize(m_maxFileSpace)));
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
        m_table->setItem(row, 4, new QTableWidgetItem(cleared ? QStringLiteral("已过期/已清除")
                                                               : QStringLiteral("有效")));
        m_table->setItem(row, 5, new QTableWidgetItem(createdAt));
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
        QMessageBox::information(this, QStringLiteral("提示"), QStringLiteral("请先勾选要删除的文件"));
        return;
    }

    if (QMessageBox::question(this, QStringLiteral("确认删除"),
                              QStringLiteral("确定删除选中的文件吗？\n删除后聊天记录会保留，但文件将不可下载。"))
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

QString RoomFileManagerDialog::fileTypeFromName(const QString &fileName) {
    const QString suffix = fileName.section('.', -1).toLower();
    static const QStringList imageExt = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList videoExt = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};

    if (imageExt.contains(suffix)) return QStringLiteral("图片");
    if (videoExt.contains(suffix)) return QStringLiteral("视频");
    return QStringLiteral("文件");
}
