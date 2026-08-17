#include "RoomPasswordPromptDialog.h"
#include "WindowsLocaleViewModel.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QVBoxLayout>

RoomPasswordPromptDialog::RoomPasswordPromptDialog(
    int roomId, QWidget *parent, WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_roomId(roomId), m_localeViewModel(localeViewModel) {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    setModal(true);
    setMinimumWidth(360);

    auto *layout = new QVBoxLayout(this);
    m_promptLabel = new QLabel(this);
    m_promptLabel->setWordWrap(true);
    layout->addWidget(m_promptLabel);

    m_passwordEdit = new QLineEdit(this);
    m_passwordEdit->setEchoMode(QLineEdit::Password);
    layout->addWidget(m_passwordEdit);

    m_statusLabel = new QLabel(this);
    m_statusLabel->setWordWrap(true);
    m_statusLabel->setStyleSheet(QStringLiteral("color: #c62828;"));
    layout->addWidget(m_statusLabel);

    auto *actions = new QHBoxLayout;
    actions->addStretch();
    m_cancelButton = new QPushButton(this);
    m_joinButton = new QPushButton(this);
    m_joinButton->setDefault(true);
    actions->addWidget(m_cancelButton);
    actions->addWidget(m_joinButton);
    layout->addLayout(actions);

    connect(m_joinButton, &QPushButton::clicked,
            this, &RoomPasswordPromptDialog::submit);
    connect(m_cancelButton, &QPushButton::clicked,
            this, &RoomPasswordPromptDialog::cancel);
    connect(m_passwordEdit, &QLineEdit::returnPressed,
            this, &RoomPasswordPromptDialog::submit);
    connect(m_passwordEdit, &QLineEdit::textChanged, this, [this] {
        m_statusLabel->clear();
    });
    if (m_localeViewModel) {
        connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
                this, &RoomPasswordPromptDialog::applyLocale);
    }
    applyLocale();
    m_passwordEdit->setFocus();
}

void RoomPasswordPromptDialog::applyLocale() {
    if (m_localeViewModel) m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.roomPasswordRequiredTitle);
    m_promptLabel->setText(copy.roomPasswordRequiredPrompt);
    m_passwordEdit->setPlaceholderText(copy.roomPasswordJoinPlaceholder);
    m_passwordEdit->setAccessibleName(copy.roomPasswordJoinAccessible);
    m_statusLabel->setAccessibleName(copy.roomPasswordJoinStatusAccessible);
    if (!m_statusLabel->text().isEmpty()) {
        m_statusLabel->setText(copy.roomPasswordJoinRequired);
    }
    m_joinButton->setText(copy.roomPasswordJoinAction);
    m_cancelButton->setText(copy.cancel);
}

void RoomPasswordPromptDialog::submit() {
    const QString password = m_passwordEdit->text();
    if (password.isEmpty()) {
        m_statusLabel->setText(
            WindowsLocaleCatalog::messages(m_locale).roomPasswordJoinRequired);
        return;
    }
    m_passwordEdit->clear();
    emit joinRequested(m_roomId, password);
    accept();
}

void RoomPasswordPromptDialog::cancel() {
    m_passwordEdit->clear();
    reject();
}
