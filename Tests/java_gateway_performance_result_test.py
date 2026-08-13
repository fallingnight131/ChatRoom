#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from java_gateway_performance_result import EvidenceError, validate


REVISION = "a" * 40


def valid_result() -> dict:
    distribution = {
        "samples": 3, "min": 1, "p50": 2, "p95": 3,
        "p99": 4, "max": 5, "mean": 2.5,
    }
    setup = dict(distribution, samples=2)
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-gateway-messaging",
        "startedAt": "2026-08-14T00:00:00Z",
        "recordedAt": "2026-08-14T00:00:01Z",
        "sourceRevision": REVISION,
        "worktreeDirty": False,
        "warning": "loopback development evidence; not a capacity claim",
        "environment": {
            "javaVersion": "21", "vm": "OpenJDK", "os": "Linux",
            "osVersion": "1", "architecture": "x86_64", "availableProcessors": 4,
            "maximumHeapBytes": 100, "peakObservedHeapBytes": 50,
            "processCpuSeconds": 1.0, "scenarioWallSeconds": 2.0,
        },
        "host": {
            "platform": "Linux", "pythonVersion": "3.12",
            "javaPeakRssBytes": 100, "postgresPostmasterPeakRssBytes": 100,
        },
        "scenario": {
            "connections": 2, "receiversPerMessage": 1,
            "warmupOperations": 1, "messageOperations": 3,
            "payloadBytes": 256, "durableMessages": 4,
        },
        "results": {
            "connectionSetupLatencyMicros": setup,
            "submitToAcceptLatencyMicros": distribution,
            "submitToPeerPublishLatencyMicros": distribution,
            "completedMessageThroughputPerSecond": 10.0,
            "errors": 0,
        },
    }


def valid_group_result() -> dict:
    result = copy.deepcopy(valid_result())
    result["schemaVersion"] = 2
    result["scenario"].update({
        "connections": 5,
        "receiversPerMessage": 4,
        "conversationKind": "GROUP",
    })
    result["results"]["connectionSetupLatencyMicros"]["samples"] = 5
    result["results"]["submitToAllPeersPublishedLatencyMicros"] = (
        result["results"].pop("submitToPeerPublishLatencyMicros")
    )
    result["results"]["peerPublications"] = 12
    return result


def valid_reconnect_result() -> dict:
    result = valid_group_result()
    result["schemaVersion"] = 3
    result["scenario"].update({"reconnectRounds": 2, "reconnectOperations": 10})
    result["results"].update({
        "sessionResumeLatencyMicros": {
            "samples": 10, "min": 1, "p50": 2, "p95": 3,
            "p99": 4, "max": 5, "mean": 2.5,
        },
        "sessionResumeThroughputPerSecond": 20.0,
        "resumeErrors": 0,
    })
    return result


def valid_paced_reconnect_result() -> dict:
    result = valid_reconnect_result()
    result["schemaVersion"] = 8
    result["scenario"].update({
        "reconnectBatchSize": 2,
        "reconnectBatchIntervalMillis": 100,
        "reconnectBatchesPerRound": 3,
        "scheduledReconnectSpanMillis": 200,
        "scheduledReconnectBatchRatePerSecond": 10.0,
    })
    result["results"]["sessionResumeArrivalJitterMicros"] = {
        "samples": 10, "min": 1, "p50": 2, "p95": 3,
        "p99": 4, "max": 5, "mean": 2.5,
    }
    return result


def valid_slow_consumer_result() -> dict:
    result = valid_group_result()
    result["schemaVersion"] = 4
    result["scenario"].update({
        "slowConsumerMaxMessages": 10,
        "slowConsumerMessagesBeforeClosure": 5,
        "slowConsumerHealthyReceivers": 3,
        "durableMessages": 10,
    })
    result["results"].update({
        "slowConsumerHealthyPublishLatencyMicros": {
            "samples": 5, "min": 1, "p50": 2, "p95": 3,
            "p99": 4, "max": 5, "mean": 2.5,
        },
        "slowConsumerRecoveryProbeLatencyMicros": {
            "samples": 1, "min": 2, "p50": 2, "p95": 2,
            "p99": 2, "max": 2, "mean": 2.0,
        },
        "slowConsumerHealthyPeerPublications": 15,
        "slowConsumerRecoveredHistoryMessages": 5,
        "slowConsumerClosed": 1,
        "slowConsumerErrors": 0,
    })
    return result


def valid_postgres_saturation_result() -> dict:
    result = valid_group_result()
    result["schemaVersion"] = 5
    result["scenario"].update({
        "connections": 9,
        "postgresSaturationSenders": 4,
        "postgresPoolMaximum": 2,
        "postgresConnectionTimeoutMillis": 1000,
        "postgresInjectedDelayMillis": 2000,
        "durableMessages": 8,
    })
    result["results"]["connectionSetupLatencyMicros"]["samples"] = 9
    result["results"].update({
        "postgresSaturationAcceptLatencyMicros": {
            "samples": 4, "min": 1, "p50": 2, "p95": 3,
            "p99": 4, "max": 5, "mean": 2.5,
        },
        "postgresSaturationPeerPublications": 16,
        "postgresSaturationUnavailableReadinessStatus": 503,
        "postgresSaturationRecoveredReadinessStatus": 200,
        "postgresSaturationRetryableFailures": 2,
        "postgresSaturationConvergedRetries": 2,
        "postgresSaturationErrors": 0,
    })
    return result


def valid_postgres_outage_result() -> dict:
    result = valid_group_result()
    result["schemaVersion"] = 6
    result["scenario"].update({
        "postgresOutage": True,
        "postgresOutageRetryOnOriginalConnection": True,
        "postgresPoolMaximum": 2,
        "postgresConnectionTimeoutMillis": 1000,
        "durableMessages": 5,
    })
    result["results"].update({
        "postgresOutageFailureLatencyMicros": {
            "samples": 1, "min": 1000, "p50": 1000, "p95": 1000,
            "p99": 1000, "max": 1000, "mean": 1000.0,
        },
        "postgresOutageRecoveryLatencyMicros": {
            "samples": 1, "min": 2000, "p50": 2000, "p95": 2000,
            "p99": 2000, "max": 2000, "mean": 2000.0,
        },
        "postgresOutageUnavailableReadinessStatus": 503,
        "postgresOutageAvailableLivenessStatus": 200,
        "postgresOutageRecoveredReadinessStatus": 200,
        "postgresOutagePeerPublications": 4,
        "postgresOutageRetryableFailures": 1,
        "postgresOutageConvergedRetries": 1,
        "postgresOutageErrors": 0,
    })
    return result


def valid_active_conversations_result() -> dict:
    result = valid_group_result()
    result["schemaVersion"] = 7
    result["scenario"].update({
        "activeConversations": 2,
        "memberships": 10,
        "routingSubscriptions": 8,
        "durableMessagesPerConversation": 2,
        "messageOperations": 4,
    })
    result["scenario"]["warmupOperations"] = 0
    result["results"]["submitToAcceptLatencyMicros"]["samples"] = 4
    result["results"]["submitToAllPeersPublishedLatencyMicros"]["samples"] = 4
    result["results"]["peerPublications"] = 16
    result["results"]["conversationActivationLatencyMicros"] = {
        "samples": 4, "min": 1, "p50": 2, "p95": 3,
        "p99": 4, "max": 5, "mean": 2.5,
    }
    return result


class GatewayPerformanceEvidenceTest(unittest.TestCase):
    def test_accepts_valid_clean_evidence(self) -> None:
        self.assertEqual(REVISION, validate(
            valid_result(), REVISION, require_clean=True)["sourceRevision"])
        self.assertEqual(2, validate(
            valid_group_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(3, validate(
            valid_reconnect_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(4, validate(
            valid_slow_consumer_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(5, validate(
            valid_postgres_saturation_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(6, validate(
            valid_postgres_outage_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(7, validate(
            valid_active_conversations_result(), REVISION, require_clean=True)["schemaVersion"])
        self.assertEqual(8, validate(
            valid_paced_reconnect_result(), REVISION, require_clean=True)["schemaVersion"])

    def test_rejects_semantic_mismatch_and_secret_content(self) -> None:
        mutations = []
        wrong_messages = copy.deepcopy(valid_result())
        wrong_messages["scenario"]["durableMessages"] = 5
        mutations.append(wrong_messages)
        wrong_samples = copy.deepcopy(valid_result())
        wrong_samples["results"]["submitToPeerPublishLatencyMicros"]["samples"] = 2
        mutations.append(wrong_samples)
        errors = copy.deepcopy(valid_result())
        errors["results"]["errors"] = 1
        mutations.append(errors)
        leak = copy.deepcopy(valid_result())
        leak["host"]["certificate"] = "/tmp/cert.pem"
        mutations.append(leak)
        wrong_group = valid_group_result()
        wrong_group["results"]["peerPublications"] = 11
        mutations.append(wrong_group)
        oversized_group = valid_group_result()
        oversized_group["scenario"]["receiversPerMessage"] = 60
        oversized_group["scenario"]["connections"] = 61
        mutations.append(oversized_group)
        oversized_text = valid_result()
        oversized_text["scenario"]["payloadBytes"] = 65_537
        mutations.append(oversized_text)
        wrong_reconnect = valid_reconnect_result()
        wrong_reconnect["scenario"]["reconnectOperations"] = 9
        mutations.append(wrong_reconnect)
        excessive_authentication = valid_reconnect_result()
        excessive_authentication["scenario"].update({
            "connections": 21, "receiversPerMessage": 20,
            "reconnectRounds": 2, "reconnectOperations": 42,
        })
        excessive_authentication["results"].update({
            "peerPublications": 400,
            "connectionSetupLatencyMicros": {
                "samples": 21, "min": 1, "p50": 2, "p95": 3,
                "p99": 4, "max": 5, "mean": 2.5,
            },
            "sessionResumeLatencyMicros": {
                "samples": 42, "min": 1, "p50": 2, "p95": 3,
                "p99": 4, "max": 5, "mean": 2.5,
            },
        })
        mutations.append(excessive_authentication)
        wrong_slow_recovery = valid_slow_consumer_result()
        wrong_slow_recovery["results"]["slowConsumerRecoveredHistoryMessages"] = 4
        mutations.append(wrong_slow_recovery)
        ready_while_saturated = valid_postgres_saturation_result()
        ready_while_saturated["results"][
            "postgresSaturationUnavailableReadinessStatus"] = 200
        mutations.append(ready_while_saturated)
        duplicate_outage_publication = valid_postgres_outage_result()
        duplicate_outage_publication["results"]["postgresOutagePeerPublications"] = 5
        mutations.append(duplicate_outage_publication)
        wrong_subscriptions = valid_active_conversations_result()
        wrong_subscriptions["scenario"]["routingSubscriptions"] = 7
        mutations.append(wrong_subscriptions)
        wrong_reconnect_batches = valid_paced_reconnect_result()
        wrong_reconnect_batches["scenario"]["reconnectBatchesPerRound"] = 2
        mutations.append(wrong_reconnect_batches)
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with self.assertRaises(EvidenceError):
                    validate(mutation, REVISION)

    def test_clean_gate_rejects_dirty_development_result(self) -> None:
        dirty = valid_result()
        dirty["worktreeDirty"] = True
        validate(dirty, REVISION)
        with self.assertRaises(EvidenceError):
            validate(dirty, REVISION, require_clean=True)


if __name__ == "__main__":
    unittest.main()
