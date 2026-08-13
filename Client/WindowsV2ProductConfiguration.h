#pragma once

#include <QString>
#include <QUrl>

class WindowsV2ProductConfiguration final {
public:
    struct Value {
        bool enabled = false;
        bool messageForwardingEnabled = false;
        QUrl endpoint;
        QString error;
    };

    static Value fromBuild();
    static Value validate(const QString &endpoint);
};
