#include "WindowsLocaleCatalog.h"
#include "WindowsLocalePreferenceRepository.h"

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
        if (repository.load() != WindowsLocale::EnUs
                || settings.value(QStringLiteral("ui/locale")).toString()
                    != QStringLiteral("en-US")) {
            qCritical() << "exact locale preference did not survive restart";
            return 1;
        }
        const auto &english = WindowsLocaleCatalog::messages(repository.load());
        if (english.sendMessage != QStringLiteral("Send message")
                || english.bytesUsed != QStringLiteral("%1 / %2 bytes")) {
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
