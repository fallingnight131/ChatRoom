#pragma once

#include <QObject>
#include <QDialog>
#include <QJsonArray>
#include "WindowsLocaleCatalog.h"

class QTableWidget;
class QLabel;
class QPushButton;
class WindowsLocaleViewModel;

class RoomFileManagerDialog : public QDialog {
    Q_OBJECT
public:
    explicit RoomFileManagerDialog(
        QWidget *parent = nullptr,
        WindowsLocaleViewModel *localeViewModel = nullptr);

    void setRoomInfo(int roomId, qint64 usedFileSpace, qint64 maxFileSpace);
    void setFiles(const QJsonArray &files);
    QLabel *summaryForTest() const { return m_summaryLabel; }
    QTableWidget *tableForTest() const { return m_table; }
    QPushButton *refreshForTest() const { return m_refreshBtn; }
    QPushButton *deleteForTest() const { return m_deleteBtn; }

signals:
    void refreshRequested(int roomId);
    void deleteRequested(int roomId, const QJsonArray &fileIds);

private slots:
    void onRefresh();
    void onDeleteSelected();

private:
    void applyLocale();
    void updateSummary();
    void updateLocalizedRows();
    static QString formatSize(qint64 bytes);
    QString fileTypeFromName(const QString &fileName) const;

    int m_roomId = 0;
    qint64 m_usedFileSpace = 0;
    qint64 m_maxFileSpace = 0;

    QLabel *m_summaryLabel = nullptr;
    QTableWidget *m_table = nullptr;
    QPushButton *m_refreshBtn = nullptr;
    QPushButton *m_deleteBtn = nullptr;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
};
