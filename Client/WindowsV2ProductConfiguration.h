#pragma once

#include <QString>
#include <QList>
#include <QUrl>

class WindowsV2ProductConfiguration final {
public:
    struct Value {
        bool enabled = false;
        bool messageForwardingEnabled = false;
        QUrl endpoint;
        QList<QUrl> fallbackEndpoints;
        QString error;
    };

    static Value fromBuild();
    static Value validate(const QString &endpoint, const QString &fallbackEndpoint = {});
};
