#pragma once

#include <QDialog>
#include <QString>
#include <QVector>

#include "V2WindowsConversationDirectoryViewModel.h"

class QLabel;
class QListWidget;
class QListWidgetItem;
class QPushButton;

class V2WindowsForwardTargetDialog final : public QDialog {
    Q_OBJECT
public:
    explicit V2WindowsForwardTargetDialog(
        const QVector<V2WindowsConversationDirectoryViewModel::Row> &authorizedRows,
        const QString &sourceConversationId, QWidget *parent = nullptr,
        bool forwardingEnabled = false,
        WindowsLocale locale = WindowsLocale::ZhCn);

    QString selectedConversationId() const { return m_selectedConversationId; }
    QListWidget *targetListForTest() const { return m_targets; }
    QPushButton *forwardForTest() const { return m_forward; }
    QLabel *statusForTest() const { return m_status; }

private:
    void updateSelection();
    void submitSelection();

    QLabel *m_status;
    QListWidget *m_targets;
    QPushButton *m_forward;
    QString m_selectedConversationId;
};
