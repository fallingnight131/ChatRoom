#pragma once

#include <QDialog>
#include "WindowsLocaleCatalog.h"

class QLineEdit;
class QDoubleSpinBox;
class QSpinBox;
class QLabel;
class QPushButton;
class QGroupBox;
class WindowsLocaleViewModel;

/// 房间设置对话框
class RoomSettingsDialog : public QDialog {
    Q_OBJECT
public:
    explicit RoomSettingsDialog(int roomId, const QString &roomName,
                                bool isAdmin,
                                qint64 maxFileSize,
                                qint64 totalFileSpace,
                                int maxFileCount,
                                int maxMembers,
                                QWidget *parent = nullptr,
                                WindowsLocaleViewModel *localeViewModel = nullptr);

    int roomId() const { return m_roomId; }
    void setRoomName(const QString &roomName);
    QLineEdit *nameForTest() const { return m_nameEdit; }
    QLineEdit *passwordForTest() const { return m_passwordEdit; }
    QLineEdit *developerKeyForTest() const { return m_developerKeyEdit; }
    QGroupBox *currentLimitsForTest() const { return m_limitsGroup; }
    QGroupBox *administratorForTest() const { return m_adminGroup; }
    QPushButton *leaveForTest() const { return m_leaveButton; }
    QPushButton *closeForTest() const { return m_closeButton; }

signals:
    void leaveRoomRequested(int roomId);
    void deleteRoomRequested(int roomId, const QString &roomName);
    void roomLimitsSaveRequested(int roomId);

private slots:
    void onSaveName();
    void onSaveLimits();
    void onSetPassword();
    void onViewPassword();
    void onUploadAvatar();

private:
    void applyLocale();
    int     m_roomId;
    QString m_roomName;
    bool    m_isAdmin;

    QLabel          *m_roomIdLabel    = nullptr;
    QLabel          *m_roomNameLabel  = nullptr;
    QLabel          *m_avatarPreview  = nullptr;
    QLineEdit       *m_nameEdit      = nullptr;
    QDoubleSpinBox  *m_fileSizeSpin  = nullptr;
    QDoubleSpinBox  *m_totalSpaceSpin = nullptr;
    QSpinBox        *m_fileCountSpin = nullptr;
    QSpinBox        *m_memberLimitSpin = nullptr;
    QLineEdit       *m_developerKeyEdit = nullptr;
    QLineEdit       *m_passwordEdit  = nullptr;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
    QGroupBox *m_limitsGroup = nullptr;
    QGroupBox *m_limitsEditGroup = nullptr;
    QGroupBox *m_adminGroup = nullptr;
    QLabel *m_maxFileSizeLabel = nullptr;
    QLabel *m_totalFileSpaceLabel = nullptr;
    QLabel *m_maxFileCountLabel = nullptr;
    QLabel *m_maxMembersLabel = nullptr;
    QLabel *m_editMaxFileSizeLabel = nullptr;
    QLabel *m_editTotalFileSpaceLabel = nullptr;
    QLabel *m_editFileCountLabel = nullptr;
    QLabel *m_editMemberLimitLabel = nullptr;
    QLabel *m_developerKeyLabel = nullptr;
    QLabel *m_roomAvatarLabel = nullptr;
    QLabel *m_nameLabel = nullptr;
    QLabel *m_passwordLabel = nullptr;
    QPushButton *m_saveLimitsButton = nullptr;
    QPushButton *m_uploadAvatarButton = nullptr;
    QPushButton *m_saveNameButton = nullptr;
    QPushButton *m_setPasswordButton = nullptr;
    QPushButton *m_viewPasswordButton = nullptr;
    QPushButton *m_leaveButton = nullptr;
    QPushButton *m_deleteButton = nullptr;
    QPushButton *m_closeButton = nullptr;
};
