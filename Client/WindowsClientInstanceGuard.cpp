#include "WindowsClientInstanceGuard.h"

#ifdef Q_OS_WIN
#include <windows.h>
#endif

QString WindowsClientInstanceGuard::mutexName() {
    return QStringLiteral("Local\\ChatRoom.WindowsClient.Running.v1");
}

WindowsClientInstanceGuard::~WindowsClientInstanceGuard() {
#ifdef Q_OS_WIN
    if (m_handle) CloseHandle(static_cast<HANDLE>(m_handle));
#endif
}

WindowsClientInstanceGuard::Result WindowsClientInstanceGuard::acquire(
        QString *error) {
    if (error) error->clear();
#ifndef Q_OS_WIN
    if (error) *error = QStringLiteral("Windows client instance guard requires Windows");
    return Result::UnsupportedPlatform;
#else
    if (m_handle) return Result::Acquired;
    const QString name = mutexName();
    const HANDLE handle = CreateMutexW(
        nullptr, FALSE, reinterpret_cast<LPCWSTR>(name.utf16()));
    if (!handle) {
        if (error) *error = QStringLiteral("Windows client liveness mutex could not be created");
        return Result::Error;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        CloseHandle(handle);
        return Result::AlreadyRunning;
    }
    m_handle = handle;
    return Result::Acquired;
#endif
}

bool WindowsClientInstanceGuard::isAcquired() const {
    return m_handle != nullptr;
}
