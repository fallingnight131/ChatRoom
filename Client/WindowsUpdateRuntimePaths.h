#pragma once

#include <QString>

struct WindowsUpdateRuntimePaths {
    QString lifecycleStateDirectory;
    QString resultDirectory;
    QString runRootDirectory;
    QString stagingDirectory;

    static WindowsUpdateRuntimePaths fromAppLocalData(
        const QString &appLocalDataDirectory);
};
