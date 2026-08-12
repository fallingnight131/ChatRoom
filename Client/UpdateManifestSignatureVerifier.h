#pragma once

#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QString>

class UpdateManifestSignatureVerifier {
public:
    using TrustedKeys = QHash<QString, QByteArray>;

    explicit UpdateManifestSignatureVerifier(TrustedKeys trustedKeys = {});

    bool verify(const QByteArray &canonicalManifest,
                const QByteArray &signature,
                QJsonObject *verifiedManifest = nullptr,
                QString *error = nullptr) const;

private:
    static QByteArray canonicalJson(const QJsonValue &value, bool *ok);
    static void fail(QString *error, const QString &message);

    TrustedKeys m_trustedKeys;
};
