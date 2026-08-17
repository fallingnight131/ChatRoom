#pragma once

#include "WindowsLocaleCatalog.h"

#include <QString>

class WindowsAttachmentPresentation final {
public:
    static QString unavailableText(WindowsLocale locale,
                                   const QString &safeServerReason);
};
