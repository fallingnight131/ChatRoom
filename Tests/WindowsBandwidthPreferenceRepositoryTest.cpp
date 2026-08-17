#include "WindowsBandwidthPolicy.h"
#include "WindowsBandwidthPreferenceRepository.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QSettings>
#include <QTemporaryDir>

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    QTemporaryDir temporary;
    if (!temporary.isValid()) return 1;
    const QString path = QDir(temporary.path()).filePath(QStringLiteral("ui.ini"));

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsBandwidthPreferenceRepository repository(settings);
        if (repository.load()) {
            qCritical() << "missing bandwidth preference did not default off";
            return 1;
        }
        settings.setValue(QStringLiteral("ui/lowBandwidth"), QStringLiteral("TRUE"));
        if (repository.load()) {
            qCritical() << "non-exact bandwidth preference did not fail closed";
            return 1;
        }
        if (!repository.save(true)) return 1;
    }

    {
        QSettings settings(path, QSettings::IniFormat);
        WindowsBandwidthPreferenceRepository repository(settings);
        if (!repository.load()
                || settings.value(QStringLiteral("ui/lowBandwidth")).toString()
                    != QStringLiteral("true")) {
            qCritical() << "bandwidth preference did not survive restart";
            return 1;
        }
    }

    {
        QSettings settings(temporary.path(), QSettings::IniFormat);
        WindowsBandwidthPreferenceRepository repository(settings);
        if (repository.save(true) || repository.load()) {
            qCritical() << "failed bandwidth save did not preserve the default";
            return 1;
        }
    }

    const QString accountId = QStringLiteral("alice");
    if (!WindowsBandwidthPolicy::shouldAutoRequestAvatar(accountId, false, false)
            || WindowsBandwidthPolicy::shouldAutoRequestAvatar(accountId, true, false)
            || WindowsBandwidthPolicy::shouldAutoRequestAvatar(accountId, false, true)
            || WindowsBandwidthPolicy::shouldAutoRequestAvatar({}, false, false)) {
        qCritical() << "automatic avatar request policy changed";
        return 1;
    }

    qInfo() << "[WindowsBandwidthPreferenceRepositoryTest] PASS";
    return 0;
}
