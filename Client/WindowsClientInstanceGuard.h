#pragma once

#include <QString>

class WindowsClientInstanceGuard {
public:
    enum class Result {
        Acquired,
        AlreadyRunning,
        UnsupportedPlatform,
        Error
    };

    WindowsClientInstanceGuard() = default;
    ~WindowsClientInstanceGuard();
    WindowsClientInstanceGuard(const WindowsClientInstanceGuard &) = delete;
    WindowsClientInstanceGuard &operator=(const WindowsClientInstanceGuard &) = delete;

    Result acquire(QString *error = nullptr);
    bool isAcquired() const;
    static QString mutexName();

private:
    void *m_handle = nullptr;
};
