#include "WindowsMessagePresentation.h"

#include <QtGlobal>

QString WindowsMessagePresentation::timestamp(WindowsLocale locale,
                                               const QDateTime &timestamp,
                                               const QDate &today) {
    const auto &copy = WindowsLocaleCatalog::messages(locale);
    const QDate date = timestamp.date();
    const QString time = timestamp.toString(QStringLiteral("HH:mm"));
    if (date == today)
        return time;
    if (date == today.addDays(-1))
        return copy.mainMessageYesterday.arg(time);
    if (date.year() == today.year())
        return copy.mainMessageDateThisYear.arg(date.month()).arg(date.day()).arg(time);
    return copy.mainMessageDateOtherYear.arg(date.year()).arg(date.month())
        .arg(date.day()).arg(time);
}

QString WindowsMessagePresentation::timestampWithDelivery(
        WindowsLocale locale, const QDateTime &timestamp,
        WindowsMessageDeliveryState deliveryState, bool isMine, bool hasServerId,
        const QDate &today) {
    QString result = WindowsMessagePresentation::timestamp(locale, timestamp, today);
    if (!isMine) return result;
    const auto &copy = WindowsLocaleCatalog::messages(locale);
    if (deliveryState == WindowsMessageDeliveryState::Sending)
        result += copy.mainMessageSendingSuffix;
    else if (deliveryState == WindowsMessageDeliveryState::Failed)
        result += copy.mainMessageFailedSuffix;
    else if (deliveryState == WindowsMessageDeliveryState::Read)
        result += copy.mainMessageReadSuffix;
    else if (hasServerId)
        result += copy.mainMessageSentSuffix;
    return result;
}

QString WindowsMessagePresentation::transferStatus(
        WindowsLocale locale, const QString &sizeText,
        WindowsMessageTransferState state, double progress, bool cached) {
    const auto &copy = WindowsLocaleCatalog::messages(locale);
    const int percent = qBound(0, static_cast<int>(progress * 100), 100);
    if (state == WindowsMessageTransferState::Downloading)
        return sizeText + copy.mainMessageDownloadingSuffix.arg(percent);
    if (state == WindowsMessageTransferState::Paused)
        return sizeText + copy.mainMessageDownloadPausedSuffix.arg(percent);
    if (state == WindowsMessageTransferState::Uploading)
        return sizeText + copy.mainMessageUploadingSuffix.arg(percent);
    if (state == WindowsMessageTransferState::UploadPaused)
        return sizeText + copy.mainMessageUploadPausedSuffix.arg(percent);
    if (!cached && state != WindowsMessageTransferState::Downloaded)
        return sizeText + copy.mainMessageClickToDownloadSuffix;
    return sizeText;
}

QString WindowsMessagePresentation::recalledText(
        WindowsLocale locale, const QString &senderName) {
    return WindowsLocaleCatalog::messages(locale).mainMessageRecalled.arg(senderName);
}
