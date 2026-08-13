#include "V2WindowsConversationParticipantViewModel.h"

#include <QCoreApplication>
#include <iostream>

namespace {
int failures = 0;
void check(bool condition, const char *message) {
    if (!condition) { ++failures; std::cerr << message << '\n'; }
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    QVector<QPair<QString, bool>> requests;
    V2WindowsConversationParticipantViewModel model(
        [&](const QString &conversationId, bool continuation) {
            requests.append({conversationId, continuation});
            return true;
        });
    check(model.activate(QStringLiteral("50000000-0000-4000-8000-000000000001"))
              && model.busy() && requests.size() == 1 && !requests.front().second,
          "activation must start an explicit first page");
    model.applyPage(model.conversationId(), {
        {QStringLiteral("60000000-0000-4000-8000-000000000002"),
         QStringLiteral("李"), QStringLiteral("成员")},
        {QStringLiteral("60000000-0000-4000-8000-000000000001"),
         QStringLiteral("Alice"), QStringLiteral("群主")}}, false, true);
    check(model.rows().size() == 2 && model.rows().front().displayName == QStringLiteral("Alice")
              && model.hasMore() && model.loadMore() && requests.back().second,
          "participant pages must merge in stable account order and continue explicitly");
    model.applyFailure(QStringLiteral("other"), QStringLiteral("stale"));
    check(model.busy(), "stale conversation failure must not mutate active state");
    model.setUnavailable();
    check(!model.busy() && !model.hasMore() && !model.failure().isEmpty(),
          "disconnect must abandon loading and expose fixed unavailable state");
    if (failures) return 1;
    std::cout << "[V2WindowsConversationParticipantViewModelTest] PASS\n";
    return 0;
}
