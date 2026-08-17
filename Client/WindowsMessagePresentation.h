#pragma once

#include "WindowsLocaleCatalog.h"

#include <QDate>
#include <QDateTime>
#include <QString>

enum class WindowsMessageDeliveryState {
    Accepted,
    Sending,
    Failed,
    Read,
};

enum class WindowsMessageTransferState {
    NotDownloaded,
    Downloading,
    Downloaded,
    Paused,
    Uploading,
    UploadPaused,
};

class WindowsMessagePresentation final {
public:
    static QString timestamp(WindowsLocale locale, const QDateTime &timestamp,
                             const QDate &today = QDate::currentDate());
    static QString timestampWithDelivery(
        WindowsLocale locale, const QDateTime &timestamp,
        WindowsMessageDeliveryState deliveryState, bool isMine, bool hasServerId,
        const QDate &today = QDate::currentDate());
    static QString transferStatus(WindowsLocale locale, const QString &sizeText,
                                  WindowsMessageTransferState state, double progress,
                                  bool cached);
    static QString recalledText(WindowsLocale locale, const QString &senderName);
};
