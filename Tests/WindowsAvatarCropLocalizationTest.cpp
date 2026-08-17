#include "AvatarCropDialog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDir>
#include <QLabel>
#include <QPixmap>
#include <QPushButton>
#include <QSettings>
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
    QPixmap source(400, 300);
    source.fill(Qt::cyan);
    AvatarCropDialog dialog(source, nullptr, &viewModel);

    const QPixmap before = dialog.croppedAvatar();
    if (!check(dialog.windowTitle() == QStringLiteral("裁剪头像")
                   && dialog.previewLabelForTest()->text()
                       == QStringLiteral("预览：")
                   && dialog.confirmForTest()->text() == QStringLiteral("确定")
                   && !dialog.accessibleDescription().isEmpty()
                   && !dialog.previewForTest()->accessibleName().isEmpty()
                   && before.size() == QSize(256, 256),
               QStringLiteral("avatar cropper must expose Chinese semantics")))
        return 1;

    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    const QPixmap after = dialog.croppedAvatar();
    if (!check(dialog.windowTitle() == QStringLiteral("Crop avatar")
                   && dialog.previewLabelForTest()->text()
                       == QStringLiteral("Preview:")
                   && dialog.confirmForTest()->text() == QStringLiteral("Confirm")
                   && dialog.cancelForTest()->text() == QStringLiteral("Cancel")
                   && dialog.accessibleDescription().contains(
                       QStringLiteral("mouse wheel"))
                   && after.toImage() == before.toImage(),
               QStringLiteral("locale switch must not mutate the cropped image")))
        return 1;

    qInfo() << "[WindowsAvatarCropLocalizationTest] PASS";
    return 0;
}
