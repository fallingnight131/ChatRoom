#include "V2WindowsForwardTargetDialog.h"

#include <QApplication>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QDebug>

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    using Row = V2WindowsConversationDirectoryViewModel::Row;
    const QVector<Row> rows{
        {QStringLiteral("conversation-source"), QStringLiteral("原会话"),
         QStringLiteral("群聊"), QStringLiteral("成员"), 3},
        {QStringLiteral("conversation-target"), QStringLiteral("产品组"),
         QStringLiteral("群聊"), QStringLiteral("管理员"), 2},
        {QStringLiteral("conversation-target"), QStringLiteral("重复项"),
         QStringLiteral("群聊"), QString(), 0},
        {QString(), QStringLiteral("无效会话"), QString(), QString(), 0},
        {QStringLiteral("conversation-blank"), QStringLiteral("   "),
         QString(), QString(), 0},
        {QStringLiteral("conversation-direct"), QStringLiteral("张三"),
         QStringLiteral("单聊"), QString(), 0},
    };

    V2WindowsForwardTargetDialog defaultOff(
        rows, QStringLiteral("conversation-source"));
    if (defaultOff.targetListForTest()->isEnabled()
            || defaultOff.targetListForTest()->count() != 0
            || defaultOff.forwardForTest()->isEnabled()
            || defaultOff.statusForTest()->text() != QStringLiteral("转发功能尚未启用")) {
        qCritical() << "default-off forwarding target dialog exposed an action";
        return 1;
    }

    V2WindowsForwardTargetDialog missingSource(rows, QString(), nullptr, true);
    if (missingSource.targetListForTest()->isEnabled()
            || missingSource.forwardForTest()->isEnabled()
            || missingSource.statusForTest()->text()
                != QStringLiteral("无法加载转发目标")) {
        qCritical() << "missing source conversation did not fail closed";
        return 1;
    }

    V2WindowsForwardTargetDialog dialog(
        rows, QStringLiteral("conversation-source"), nullptr, true);
    if (dialog.targetListForTest()->accessibleName()
                != QStringLiteral("可转发的会话列表")
            || dialog.forwardForTest()->accessibleName()
                != QStringLiteral("确认转发到所选会话")
            || dialog.targetListForTest()->count() != 2) {
        qCritical() << "authorized target filtering or accessibility failed";
        return 1;
    }
    for (int index = 0; index < dialog.targetListForTest()->count(); ++index) {
        const auto *item = dialog.targetListForTest()->item(index);
        if (item->data(Qt::UserRole).toString() == QStringLiteral("conversation-source")) {
            qCritical() << "source conversation remained selectable";
            return 1;
        }
    }
    if (dialog.forwardForTest()->isEnabled()) {
        qCritical() << "forward action enabled without a target";
        return 1;
    }
    dialog.targetListForTest()->setCurrentRow(0);
    if (!dialog.forwardForTest()->isEnabled()) {
        qCritical() << "forward action did not follow selection";
        return 1;
    }
    dialog.forwardForTest()->click();
    if (dialog.result() != QDialog::Accepted
            || dialog.selectedConversationId() != QStringLiteral("conversation-target")) {
        qCritical() << "selected target was not accepted exactly";
        return 1;
    }

    qInfo() << "[V2WindowsForwardTargetDialogTest] PASS";
    return 0;
}
