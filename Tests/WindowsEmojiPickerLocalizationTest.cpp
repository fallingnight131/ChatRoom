#include "EmojiPicker.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QApplication>
#include <QDebug>
#include <QDir>
#include <QLabel>
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
    EmojiPicker picker(nullptr, &viewModel);

    const auto buttons = picker.buttonsForTest();
    if (!check(picker.titleForTest()->text() == QStringLiteral("表情")
                   && buttons.size() == 96
                   && buttons.first()->accessibleName().startsWith(
                       QStringLiteral("插入表情 ")),
               QStringLiteral("emoji picker must expose Chinese accessible copy")))
        return 1;

    const QString firstEmoji = buttons.first()->text();
    if (!viewModel.select(WindowsLocale::EnUs)) return 1;
    application.processEvents();
    if (!check(picker.titleForTest()->text() == QStringLiteral("Emoji")
                   && buttons.first()->text() == firstEmoji
                   && buttons.first()->accessibleName()
                       == QStringLiteral("Insert emoji %1").arg(firstEmoji),
               QStringLiteral("emoji picker must recompose without changing identity")))
        return 1;

    QString selected;
    QObject::connect(&picker, &EmojiPicker::emojiSelected,
                     [&](const QString &emoji) { selected = emoji; });
    buttons.first()->click();
    if (!check(selected == firstEmoji,
               QStringLiteral("localization must not change emoji selection")))
        return 1;

    qInfo() << "[WindowsEmojiPickerLocalizationTest] PASS";
    return 0;
}
