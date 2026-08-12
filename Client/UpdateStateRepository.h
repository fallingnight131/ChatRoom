#pragma once

#include <QByteArray>
#include <QHash>
#include <QString>

class UpdateStateRepository {
public:
    struct ChannelState {
        qint64 sequence = 0;
        QByteArray manifestSha256;
    };

    struct State {
        QString stableDeviceId;
        QHash<QString, ChannelState> channels;
    };

    enum class Acceptance {
        Stored,
        Idempotent,
        Rejected
    };

    explicit UpdateStateRepository(QString directoryPath);

    bool loadOrCreate(State *state, QString *error = nullptr) const;
    Acceptance accept(const QString &channel,
                      qint64 sequence,
                      const QByteArray &manifestSha256,
                      State *state = nullptr,
                      QString *error = nullptr) const;

private:
    bool prepareDirectory(QString *error) const;
    bool readState(State *state, bool allowMissing, QString *error) const;
    bool writeState(const State &state, QString *error) const;
    static bool validate(const State &state, QString *error);
    static void fail(QString *error, const QString &message);

    QString m_directoryPath;
};
