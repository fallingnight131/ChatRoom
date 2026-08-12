#include "WindowsUpdateRuntimePaths.h"

#include <QDir>

WindowsUpdateRuntimePaths WindowsUpdateRuntimePaths::fromAppLocalData(
        const QString &appLocalDataDirectory) {
    const QDir updateRoot(QDir(appLocalDataDirectory).filePath(
        QStringLiteral("updates")));
    return {
        updateRoot.filePath(QStringLiteral("lifecycle")),
        updateRoot.filePath(QStringLiteral("results")),
        updateRoot.filePath(QStringLiteral("runs")),
        updateRoot.filePath(QStringLiteral("staging"))
    };
}
