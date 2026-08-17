#include "WindowsLocaleCatalog.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QCoreApplication>
#include <QDir>
#include <QSettings>
#include <QTemporaryDir>
#include <QDebug>

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    const QString path = QDir(temporary.path()).filePath(QStringLiteral("preferences.ini"));

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        if (repository.load() != WindowsLocale::ZhCn) {
            qCritical() << "missing locale did not use the product default";
            return 1;
        }
        settings.setValue(QStringLiteral("ui/locale"), QStringLiteral("EN-us"));
        settings.sync();
        if (repository.load() != WindowsLocale::ZhCn) {
            qCritical() << "non-exact locale did not fail closed";
            return 1;
        }
        if (!repository.save(WindowsLocale::EnUs)) {
            qCritical() << "locale preference was not persisted";
            return 1;
        }
    }

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel viewModel(&repository);
        if (!viewModel.select(WindowsLocale::EnUs)
                || viewModel.locale() != WindowsLocale::EnUs
                || !viewModel.failure().isEmpty()) {
            qCritical() << "locale view model did not persist exact English";
            return 1;
        }
    }

    {
        QSettings settings(temporary.path(), QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        WindowsLocaleViewModel viewModel(&repository);
        if (viewModel.select(WindowsLocale::EnUs)
                || viewModel.locale() != WindowsLocale::ZhCn
                || repository.load() != WindowsLocale::ZhCn
                || viewModel.failure().isEmpty()) {
            qCritical() << "locale save failure did not preserve current language";
            return 1;
        }
    }

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsLocalePreferenceRepository repository(settings);
        if (repository.load() != WindowsLocale::EnUs
                || settings.value(QStringLiteral("ui/locale")).toString()
                    != QStringLiteral("en-US")) {
            qCritical() << "exact locale preference did not survive restart";
            return 1;
        }
        const auto &english = WindowsLocaleCatalog::messages(repository.load());
        if (english.sendMessage != QStringLiteral("Send message")
                || english.bytesUsed != QStringLiteral("%1 / %2 bytes")
                || english.profileTitle != QStringLiteral("Edit profile")
                || english.profileLowBandwidth != QStringLiteral("Low-bandwidth mode")
                || english.profilePasswordFieldsRequired
                    != QStringLiteral("Complete every password field")
                || english.loginWindowTitle
                    != QStringLiteral("Qt Chat Room - Sign in")
                || english.registrationSucceeded
                    != QStringLiteral(
                        "Registration succeeded! Switch to the sign-in tab")
                || english.emojiPickerTitle != QStringLiteral("Emoji")
                || english.emojiInsertAccessible
                    != QStringLiteral("Insert emoji %1")
                || english.forwardTitle
                    != QStringLiteral("Forward to another conversation")
                || english.forwardConfirm != QStringLiteral("Forward")) {
            qCritical() << "English catalog shape changed";
            return 1;
        }
        if (!repository.save(WindowsLocale::ZhCn)) return 1;
        const auto &chinese = WindowsLocaleCatalog::messages(repository.load());
        if (chinese.sendMessage != QStringLiteral("发送消息")
                || WindowsLocaleCatalog::code(repository.load())
                    != QStringLiteral("zh-CN")) {
            qCritical() << "Chinese catalog shape changed";
            return 1;
        }
    }

    qInfo() << "[WindowsLocalePreferenceRepositoryTest] PASS";
    return 0;
}
