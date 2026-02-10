#pragma once

#include <QObject>
#include <QString>

class QApplication;

/// 主题管理器 —— 单例，管理深色/浅色主题切换
class ThemeManager : public QObject {
    Q_OBJECT
public:
    enum Theme { Light, Dark };

    static ThemeManager *instance();

    Theme currentTheme() const { return m_theme; }
    void setTheme(Theme theme);
    void toggleTheme();
    void applyTheme(QApplication *app);

    QString lightStyleSheet() const;
    QString darkStyleSheet() const;

signals:
    void themeChanged(Theme theme);

private:
    explicit ThemeManager(QObject *parent = nullptr);
    static ThemeManager *s_instance;

    Theme m_theme = Dark;
};
