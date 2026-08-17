#include "WindowsAttachmentPresentation.h"

QString WindowsAttachmentPresentation::unavailableText(
        WindowsLocale locale, const QString &safeServerReason) {
    if (!safeServerReason.trimmed().isEmpty())
        return safeServerReason;
    return WindowsLocaleCatalog::messages(locale).roomFileClearedUnavailable;
}
