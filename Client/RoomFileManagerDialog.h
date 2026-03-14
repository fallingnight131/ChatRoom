#pragma once

#include <QDialog>
#include <QJsonArray>

class QTableWidget;
class QLabel;
class QPushButton;

class RoomFileManagerDialog : public QDialog {
    Q_OBJECT
public:
    explicit RoomFileManagerDialog(QWidget *parent = nullptr);

    void setRoomInfo(int roomId, qint64 usedFileSpace, qint64 maxFileSpace);
    void setFiles(const QJsonArray &files);

signals:
    void refreshRequested(int roomId);
    void deleteRequested(int roomId, const QJsonArray &fileIds);

private slots:
    void onRefresh();
    void onDeleteSelected();

private:
    static QString formatSize(qint64 bytes);
    static QString fileTypeFromName(const QString &fileName);

    int m_roomId = 0;
    qint64 m_usedFileSpace = 0;
    qint64 m_maxFileSpace = 0;

    QLabel *m_summaryLabel = nullptr;
    QTableWidget *m_table = nullptr;
    QPushButton *m_refreshBtn = nullptr;
    QPushButton *m_deleteBtn = nullptr;
};
