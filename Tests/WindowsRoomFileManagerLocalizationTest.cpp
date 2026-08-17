#include "RoomFileManagerDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QCheckBox>
#include <QDebug>
#include <QDir>
#include <QHeaderView>
#include <QJsonArray>
#include <QJsonObject>
#include <QLabel>
#include <QPushButton>
#include <QSettings>
#include <QTableWidget>
#include <QTemporaryDir>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition) qCritical().noquote() << message;
    return condition;
}
}

int main(int argc, char **argv) {
    QApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    QSettings settings(
        QDir(temporary.path()).filePath(QStringLiteral("ui.ini")),
        QSettings::IniFormat);
    WindowsLocalePreferenceRepository repository(settings);
    WindowsLocaleViewModel viewModel(&repository);
    RoomFileManagerDialog dialog(nullptr, &viewModel);
    dialog.setRoomInfo(42, 1536, 4096);
    dialog.setFiles(QJsonArray{
        QJsonObject{{"fileId", 7}, {"fileName", QStringLiteral("截图 Ω.png")},
                    {"fileSize", 1536}, {"cleared", false},
                    {"createdAt", QStringLiteral("2026-08-17T10:00:00Z")}},
        QJsonObject{{"fileId", 8}, {"fileName", QStringLiteral("旧视频.mp4")},
                    {"fileSize", 4096}, {"cleared", true},
                    {"createdAt", QStringLiteral("2026-08-16T10:00:00Z")}}
    });

    auto *table = dialog.tableForTest();
    auto *selected = qobject_cast<QCheckBox *>(table->cellWidget(0, 0));
    auto *cleared = qobject_cast<QCheckBox *>(table->cellWidget(1, 0));
    if (!check(dialog.windowTitle() == QStringLiteral("文件管理")
                   && dialog.summaryForTest()->text()
                       == QStringLiteral("当前文件空间：1.5 KB / 4.0 KB")
                   && table->horizontalHeaderItem(1)->text() == QStringLiteral("文件名")
                   && table->item(0, 2)->text() == QStringLiteral("图片")
                   && table->item(0, 4)->text() == QStringLiteral("有效")
                   && table->item(1, 4)->text() == QStringLiteral("已过期/已清除")
                   && selected && selected->isEnabled() && cleared && !cleared->isEnabled(),
               QStringLiteral("file manager must compose the complete Chinese state")))
        return 1;

    selected->setChecked(true);
    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    if (!check(dialog.windowTitle() == QStringLiteral("File manager")
                   && dialog.summaryForTest()->text()
                       == QStringLiteral("File storage: 1.5 KB / 4.0 KB")
                   && table->horizontalHeaderItem(0)->text() == QStringLiteral("Select")
                   && table->horizontalHeaderItem(5)->text() == QStringLiteral("Uploaded at")
                   && table->item(0, 1)->text() == QStringLiteral("截图 Ω.png")
                   && table->item(0, 2)->text() == QStringLiteral("Image")
                   && table->item(1, 2)->text() == QStringLiteral("Video")
                   && table->item(0, 4)->text() == QStringLiteral("Available")
                   && table->item(1, 4)->text() == QStringLiteral("Expired/cleared")
                   && selected->isChecked()
                   && dialog.refreshForTest()->text() == QStringLiteral("Refresh")
                   && dialog.deleteForTest()->text()
                       == QStringLiteral("Delete selected files"),
               QStringLiteral("live switch must preserve rows and deletion selection")))
        return 1;

    bool refreshRequested = false;
    QObject::connect(&dialog, &RoomFileManagerDialog::refreshRequested,
                     [&](int roomId) { refreshRequested = roomId == 42; });
    dialog.refreshForTest()->click();
    if (!check(refreshRequested,
               QStringLiteral("localized refresh must retain the authoritative room id")))
        return 1;

    qInfo() << "[WindowsRoomFileManagerLocalizationTest] PASS";
    return 0;
}
