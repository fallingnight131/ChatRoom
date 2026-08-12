#include "UpdateManifestApplicationService.h"

#include <utility>

namespace {
using Policy = UpdateManifestDecisionPolicy;

UpdateManifestApplicationService::Result reject(const QString &error) {
    UpdateManifestApplicationService::Result result;
    result.decision.error = error;
    return result;
}
}

UpdateManifestApplicationService::UpdateManifestApplicationService(
        UpdateManifestSignatureVerifier::TrustedKeys trustedKeys,
        QString stateDirectory)
    : m_signatureVerifier(std::move(trustedKeys)),
      m_stateRepository(std::move(stateDirectory)) {}

UpdateManifestApplicationService::Result
UpdateManifestApplicationService::evaluateAndAccept(const Request &request) const {
    QJsonObject verifiedManifest;
    QString error;
    if (!m_signatureVerifier.verify(request.canonicalManifest, request.signature,
                                    &verifiedManifest, &error))
        return reject(error);

    UpdateStateRepository::State state;
    if (!m_stateRepository.loadOrCreate(&state, &error)) return reject(error);
    const auto channelState = state.channels.value(request.channel);
    Policy::Context context;
    context.currentVersion = request.currentVersion;
    context.channel = request.channel;
    context.highestAcceptedSequence = channelState.sequence;
    context.highestAcceptedManifestSha256 = channelState.manifestSha256;
    context.stableDeviceId = state.stableDeviceId;
    context.nowUtc = request.nowUtc;

    Result result;
    result.stableDeviceId = state.stableDeviceId;
    result.decision = Policy::evaluate(request.canonicalManifest,
                                       verifiedManifest, context);
    if (result.decision.outcome == Policy::Outcome::Rejected) return result;

    const auto acceptance = m_stateRepository.accept(
        request.channel, result.decision.acceptedSequence,
        result.decision.acceptedManifestSha256, nullptr, &error);
    if (acceptance == UpdateStateRepository::Acceptance::Rejected)
        return reject(error);
    return result;
}
