#pragma once

#include <QDialog>

class QLineEdit;
class QDoubleSpinBox;
class QSpinBox;
class QLabel;
class QPushButton;
class QGroupBox;

/// 聊天室设置对话框
class RoomSettingsDialog : public QDialog {
    Q_OBJECT
public:
    explicit RoomSettingsDialog(int roomId, const QString &roomName,
                                bool isAdmin,
                                qint64 maxFileSize,
                                qint64 totalFileSpace,
                                int maxFileCount,
                                int maxMembers,
                                QWidget *parent = nullptr);

signals:
    void leaveRoomRequested(int roomId);
    void deleteRoomRequested(int roomId, const QString &roomName);

private slots:
    void onSaveName();
    void onSaveLimits();
    void onSetPassword();
    void onViewPassword();
    void onUploadAvatar();

private:
    int     m_roomId;
    QString m_roomName;
    bool    m_isAdmin;

    QLabel          *m_roomIdLabel    = nullptr;
    QLabel          *m_avatarPreview  = nullptr;
    QLineEdit       *m_nameEdit      = nullptr;
    QDoubleSpinBox  *m_fileSizeSpin  = nullptr;
    QDoubleSpinBox  *m_totalSpaceSpin = nullptr;
    QSpinBox        *m_fileCountSpin = nullptr;
    QSpinBox        *m_memberLimitSpin = nullptr;
    QLineEdit       *m_passwordEdit  = nullptr;
};
