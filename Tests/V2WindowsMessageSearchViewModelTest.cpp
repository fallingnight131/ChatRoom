#include "V2WindowsMessageSearchViewModel.h"
#include <QCoreApplication>
#include <QDebug>

namespace { int failures = 0; void check(bool value, const char *message) { if (!value) { ++failures; qCritical() << message; } } }

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QString requestedConversation, requestedQuery;
    quint64 requestedBefore = 1;
    bool requestedContinuation = true;
    QString contextMessage;
    quint64 contextSequence = 0;
    V2WindowsMessageSearchViewModel model(
        [&](const QString &conversation, const QString &query,
            quint64 before, bool continuation) {
            requestedConversation = conversation; requestedQuery = query;
            requestedBefore = before; requestedContinuation = continuation; return true;
        },
        [&](const QString &selected, quint64 sequence, const QString &messageId) {
            requestedConversation = selected;
            contextSequence = sequence;
            contextMessage = messageId;
            return true;
        });
    const QString conversation = QStringLiteral("10000000-0000-4000-8000-000000000001");
    check(model.activate(conversation), "conversation activation failed");
    check(model.search(QStringLiteral("  聊天  ")) && model.busy()
              && requestedConversation == conversation
              && requestedQuery == QStringLiteral("聊天")
              && requestedBefore == 0 && !requestedContinuation,
          "trimmed first search was not requested");
    QVector<V2WindowsMessageSearchViewModel::Row> first;
    first.append({QStringLiteral("50000000-0000-4000-8000-000000000001"), 9,
        QStringLiteral("60000000-0000-4000-8000-000000000001"),
        QStringLiteral("聊天记录"), 900, 0, 0});
    model.applyPage(conversation, QStringLiteral("聊天"), first, false, 9, true);
    check(model.rows().size() == 1 && model.hasMore() && model.loadMore()
              && requestedBefore == 9 && requestedContinuation,
          "continuation cursor was not retained");
    QVector<V2WindowsMessageSearchViewModel::Row> duplicate = first;
    duplicate.append({QStringLiteral("50000000-0000-4000-8000-000000000002"), 8,
        QStringLiteral("60000000-0000-4000-8000-000000000001"),
        QStringLiteral("更早记录"), 800, 0, 0});
    model.applyPage(conversation, QStringLiteral("聊天"), duplicate, true, 8, false);
    check(model.rows().size() == 2 && !model.hasMore(),
          "stable message identity was not deduplicated");
    check(model.requestContext(first.first().messageId) && model.contextBusy()
              && contextMessage == first.first().messageId && contextSequence == 9,
          "context request lost the selected stable identity and sequence");
    model.applyContextAvailable(contextMessage);
    check(!model.contextBusy() && model.failure().isEmpty(),
          "successful context request did not settle transient state");
    model.setUnavailable();
    check(model.rows().isEmpty() && model.query().isEmpty() && !model.busy()
              && !model.failure().isEmpty(),
          "disconnect retained transient search data");
    if (failures) return 1;
    qInfo() << "[V2WindowsMessageSearchViewModelTest] PASS";
}
