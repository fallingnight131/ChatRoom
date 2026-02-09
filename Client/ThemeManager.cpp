#include "ThemeManager.h"
#include <QApplication>
#include <QFile>

ThemeManager *ThemeManager::s_instance = nullptr;

ThemeManager *ThemeManager::instance() {
    if (!s_instance)
        s_instance = new ThemeManager();
    return s_instance;
}

ThemeManager::ThemeManager(QObject *parent)
    : QObject(parent)
{
}

void ThemeManager::setTheme(Theme theme) {
    if (m_theme != theme) {
        m_theme = theme;
        emit themeChanged(m_theme);
    }
}

void ThemeManager::toggleTheme() {
    setTheme(m_theme == Light ? Dark : Light);
}

void ThemeManager::applyTheme(QApplication *app) {
    QString qss;

    // 尝试加载资源文件中的样式表
    QString path = (m_theme == Dark) ? ":/styles/dark.qss" : ":/styles/light.qss";
    QFile file(path);
    if (file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        qss = file.readAll();
        file.close();
    } else {
        // 使用内置样式
        qss = (m_theme == Dark) ? darkStyleSheet() : lightStyleSheet();
    }

    app->setStyleSheet(qss);
}

QString ThemeManager::lightStyleSheet() const {
    return R"(
        QMainWindow, QDialog {
            background-color: #f5f5f5;
        }
        QListWidget, QListView {
            background-color: #ffffff;
            border: 1px solid #ddd;
            border-radius: 4px;
            font-size: 13px;
        }
        QListWidget::item {
            padding: 6px 8px;
            border-bottom: 1px solid #eee;
        }
        QListWidget::item:selected {
            background-color: #e3f2fd;
            color: #1976d2;
        }
        QListWidget::item:hover {
            background-color: #f5f5f5;
        }
        QTextEdit {
            background-color: #ffffff;
            border: 1px solid #ddd;
            border-radius: 4px;
            padding: 4px;
            font-size: 13px;
        }
        QLabel {
            color: #333333;
        }
        QPushButton {
            background-color: #e0e0e0;
            border: none;
            border-radius: 4px;
            padding: 6px 12px;
            font-size: 13px;
        }
        QPushButton:hover {
            background-color: #d0d0d0;
        }
        QPushButton:pressed {
            background-color: #bdbdbd;
        }
        QMenuBar {
            background-color: #ffffff;
            border-bottom: 1px solid #ddd;
        }
        QMenuBar::item:selected {
            background-color: #e3f2fd;
        }
        QMenu {
            background-color: #ffffff;
            border: 1px solid #ddd;
        }
        QMenu::item:selected {
            background-color: #e3f2fd;
        }
        QStatusBar {
            background-color: #ffffff;
            border-top: 1px solid #ddd;
        }
        QSplitter::handle {
            background-color: #e0e0e0;
            width: 2px;
        }
        QLineEdit {
            border: 1px solid #ddd;
            border-radius: 4px;
            padding: 6px;
            background: white;
        }
        QLineEdit:focus {
            border-color: #1976d2;
        }
        QTabWidget::pane {
            border: 1px solid #ddd;
            border-radius: 4px;
        }
        QTabBar::tab {
            padding: 8px 20px;
            border: 1px solid #ddd;
            border-bottom: none;
            border-radius: 4px 4px 0 0;
        }
        QTabBar::tab:selected {
            background: white;
            border-bottom: 2px solid #1976d2;
        }
    )";
}

QString ThemeManager::darkStyleSheet() const {
    return R"(
        QMainWindow, QDialog {
            background-color: #2b2b2b;
            color: #e0e0e0;
        }
        QListWidget, QListView {
            background-color: #333333;
            border: 1px solid #444;
            border-radius: 4px;
            color: #e0e0e0;
            font-size: 13px;
        }
        QListWidget::item {
            padding: 6px 8px;
            border-bottom: 1px solid #444;
        }
        QListWidget::item:selected {
            background-color: #37474f;
            color: #80cbc4;
        }
        QListWidget::item:hover {
            background-color: #3a3a3a;
        }
        QTextEdit {
            background-color: #333333;
            border: 1px solid #444;
            border-radius: 4px;
            padding: 4px;
            color: #e0e0e0;
            font-size: 13px;
        }
        QLabel {
            color: #e0e0e0;
        }
        QPushButton {
            background-color: #444444;
            border: none;
            border-radius: 4px;
            padding: 6px 12px;
            color: #e0e0e0;
            font-size: 13px;
        }
        QPushButton:hover {
            background-color: #555555;
        }
        QPushButton:pressed {
            background-color: #666666;
        }
        QMenuBar {
            background-color: #333333;
            border-bottom: 1px solid #444;
            color: #e0e0e0;
        }
        QMenuBar::item:selected {
            background-color: #37474f;
        }
        QMenu {
            background-color: #333333;
            border: 1px solid #444;
            color: #e0e0e0;
        }
        QMenu::item:selected {
            background-color: #37474f;
        }
        QStatusBar {
            background-color: #333333;
            border-top: 1px solid #444;
            color: #e0e0e0;
        }
        QSplitter::handle {
            background-color: #444444;
            width: 2px;
        }
        QLineEdit {
            border: 1px solid #555;
            border-radius: 4px;
            padding: 6px;
            background: #3a3a3a;
            color: #e0e0e0;
        }
        QLineEdit:focus {
            border-color: #80cbc4;
        }
        QTabWidget::pane {
            border: 1px solid #444;
            border-radius: 4px;
        }
        QTabBar::tab {
            padding: 8px 20px;
            border: 1px solid #444;
            border-bottom: none;
            border-radius: 4px 4px 0 0;
            color: #e0e0e0;
            background: #333;
        }
        QTabBar::tab:selected {
            background: #2b2b2b;
            border-bottom: 2px solid #80cbc4;
        }
        QScrollBar:vertical {
            background: #2b2b2b;
            width: 8px;
        }
        QScrollBar::handle:vertical {
            background: #555;
            border-radius: 4px;
        }
    )";
}
