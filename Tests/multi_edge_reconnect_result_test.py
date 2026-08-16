#!/usr/bin/env python3

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from multi_edge_reconnect_result import EvidenceError, validate  # noqa: E402


def evidence():
    distribution = {
        "samples": 12, "min": 10, "p50": 20, "p95": 30,
        "p99": 30, "max": 30, "mean": 20.0,
    }
    return {
        "schemaVersion": 1,
        "benchmark": "java-v2-haproxy-multi-edge-reconnect",
        "warning": "local dual-edge recovery evidence; not a production capacity claim",
        "recordedAt": "2026-08-14T00:00:00Z",
        "sourceRevision": "a" * 40,
        "worktreeDirty": False,
        "environment": {
            "javaVersion": "21", "os": "test", "architecture": "arm64",
            "availableProcessors": 10, "maximumHeapBytes": 1024,
        },
        "host": {
            "platform": "test", "pythonVersion": "3.9",
            "haproxyImage": (
                "haproxy:3.2-alpine@sha256:"
                "79799e8b2977e60802774fa53d29e6b54e045402cdd8a8b9fe43923e7095a047"
            ),
        },
        "scenario": {
            "edgeProcesses": 2, "gatewayProcesses": 2,
            "primaryEdgeKilled": True, "connections": 18,
            "failedEdgeConnections": 12, "survivingEdgeConnections": 6,
            "reconnectBatchSize": 3, "reconnectBatchIntervalMillis": 100,
            "reconnectBatches": 4, "scheduledReconnectSpanMillis": 300,
        },
        "results": {
            "reconnectAttempts": 12, "reconnectSuccesses": 12,
            "reconnectErrors": 0,
            "secondaryGatewayAuthenticationBefore": 6,
            "secondaryGatewayAuthenticationAfter": 18,
            "elapsedMillis": 350.0, "reconnectThroughputPerSecond": 34.2,
            "sessionResumeLatencyMicros": copy.deepcopy(distribution),
            "scheduledStartJitterMicros": copy.deepcopy(distribution),
        },
    }


def authentication_saturation():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "activeWorkersMaximum": 3,
        "queuedWorkMaximum": 0,
    }


def postgres_pool_saturation():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "metricsUnavailableSamples": 0,
        "activeConnectionsMaximum": 3,
        "totalConnectionsMaximum": 4,
        "threadsAwaitingConnectionMaximum": 0,
        "configuredMaximumConnections": 4,
    }


def event_loop_saturation():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "metricsUnavailableSamples": 0,
        "workers": 4,
        "probeSamplesBefore": 40,
        "probeSamplesAfter": 68,
        "probeSamplesDelta": 28,
        "latestMaximumLagMicros": 500,
        "sinceStartMaximumLagMicrosBefore": 300,
        "sinceStartMaximumLagMicrosAfter": 700,
        "pendingTasksMaximum": 8,
    }


def process_resource_saturation():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "cpuTimeUnavailableSamples": 0,
        "cpuTimeMicrosBefore": 1_000_000,
        "cpuTimeMicrosAfter": 1_100_000,
        "cpuTimeMicrosDelta": 100_000,
        "heapUsedBytesBefore": 100,
        "heapUsedBytesAfter": 120,
        "heapUsedBytesMaximum": 150,
        "heapCommittedBytesBefore": 200,
        "heapCommittedBytesAfter": 220,
        "heapMaximumBytes": 1024,
        "uptimeMillisBefore": 10_000,
        "uptimeMillisAfter": 10_350,
        "uptimeMillisDelta": 350,
        "availableProcessors": 10,
    }


def pressure_duration():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "authenticationQueuePositiveSamples": 0,
        "authenticationQueueLongestConsecutiveSamples": 0,
        "postgresWaitingPositiveSamples": 2,
        "postgresWaitingLongestConsecutiveSamples": 1,
        "eventLoopPendingPositiveSamples": 3,
        "eventLoopPendingLongestConsecutiveSamples": 2,
    }


def gc_collection_activity():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "metricsUnavailableSamples": 0,
        "collectionsBefore": 10,
        "collectionsAfter": 12,
        "collectionsDelta": 2,
        "collectionTimeMillisBefore": 100,
        "collectionTimeMillisAfter": 125,
        "collectionTimeMillisDelta": 25,
    }


def resident_memory_activity():
    return {
        "sampleIntervalMillis": 5,
        "configuredRefreshIntervalMillis": 250,
        "samples": 24,
        "metricsUnavailableSamples": 0,
        "residentBytesBefore": 1000,
        "residentBytesAfter": 1200,
        "residentBytesMaximum": 1400,
        "sampleAgeMillisMaximum": 249,
        "readFailuresBefore": 1,
        "readFailuresAfter": 2,
        "readFailuresDelta": 1,
    }


def schema_nine_evidence():
    value = evidence()
    value["schemaVersion"] = 9
    value["scenario"]["workloadProfile"] = "step-12"
    value["results"]["authenticationSaturation"] = authentication_saturation()
    value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
    value["results"]["postgresPoolSaturation"][
        "threadsAwaitingConnectionMaximum"] = 2
    value["results"]["eventLoopSaturation"] = event_loop_saturation()
    value["results"]["processResourceSaturation"] = process_resource_saturation()
    value["results"]["pressureDuration"] = pressure_duration()
    value["results"]["gcCollectionActivity"] = gc_collection_activity()
    value["results"]["residentMemoryActivity"] = resident_memory_activity()
    return value


def direct_buffer_activity():
    return {
        "sampleIntervalMillis": 5,
        "samples": 24,
        "metricsUnavailableSamples": 0,
        "bufferCountBefore": 10,
        "bufferCountAfter": 12,
        "bufferCountMaximum": 14,
        "memoryUsedBytesBefore": 1000,
        "memoryUsedBytesAfter": 1200,
        "memoryUsedBytesMaximum": 1400,
        "totalCapacityBytesBefore": 900,
        "totalCapacityBytesAfter": 1100,
        "totalCapacityBytesMaximum": 1300,
    }


def schema_ten_evidence():
    value = schema_nine_evidence()
    value["schemaVersion"] = 10
    value["results"]["directBufferActivity"] = direct_buffer_activity()
    return value


class MultiEdgeReconnectResultTest(unittest.TestCase):
    def test_accepts_reconciled_clean_evidence(self):
        validate(evidence(), "a" * 40, require_clean=True)

    def test_accepts_in_window_saturation_evidence(self):
        value = evidence()
        value["schemaVersion"] = 2
        value["results"]["authenticationSaturation"] = authentication_saturation()
        validate(value, "a" * 40, require_clean=True)

    def test_accepts_postgres_pool_saturation_evidence(self):
        value = evidence()
        value["schemaVersion"] = 3
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        validate(value, "a" * 40, require_clean=True)

    def test_accepts_event_loop_saturation_evidence(self):
        value = evidence()
        value["schemaVersion"] = 4
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        validate(value, "a" * 40, require_clean=True)

    def test_accepts_process_resource_saturation_evidence(self):
        value = evidence()
        value["schemaVersion"] = 5
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        validate(value, "a" * 40, require_clean=True)

    def test_accepts_each_fixed_workload_profile(self):
        workloads = {
            "step-12": (18, 12, 6, 3),
            "step-24": (30, 24, 6, 6),
            "step-48": (54, 48, 6, 12),
        }
        for profile, (connections, affected, surviving, batch) in workloads.items():
            value = evidence()
            value["schemaVersion"] = 6
            value["scenario"].update(
                workloadProfile=profile,
                connections=connections,
                failedEdgeConnections=affected,
                survivingEdgeConnections=surviving,
                reconnectBatchSize=batch,
            )
            value["results"].update(
                reconnectAttempts=affected,
                reconnectSuccesses=affected,
                secondaryGatewayAuthenticationBefore=surviving,
                secondaryGatewayAuthenticationAfter=connections,
            )
            for field in ("sessionResumeLatencyMicros", "scheduledStartJitterMicros"):
                value["results"][field]["samples"] = affected
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            value["results"]["eventLoopSaturation"] = event_loop_saturation()
            value["results"]["processResourceSaturation"] = process_resource_saturation()
            with self.subTest(profile=profile):
                validate(value, "a" * 40, require_clean=True)

    def test_accepts_duration_aware_pressure_evidence(self):
        value = evidence()
        value["schemaVersion"] = 7
        value["scenario"]["workloadProfile"] = "step-12"
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["postgresPoolSaturation"][
            "threadsAwaitingConnectionMaximum"] = 2
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        value["results"]["pressureDuration"] = pressure_duration()
        validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_inconsistent_pressure_duration(self):
        for mutate in (
            lambda duration: duration.update(samples=23),
            lambda duration: duration.update(authenticationQueuePositiveSamples=1),
            lambda duration: duration.update(postgresWaitingPositiveSamples=25),
            lambda duration: duration.update(
                postgresWaitingLongestConsecutiveSamples=3),
            lambda duration: duration.update(eventLoopPendingPositiveSamples=0),
            lambda duration: duration.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 7
            value["scenario"]["workloadProfile"] = "step-12"
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            value["results"]["postgresPoolSaturation"][
                "threadsAwaitingConnectionMaximum"] = 2
            value["results"]["eventLoopSaturation"] = event_loop_saturation()
            value["results"]["processResourceSaturation"] = process_resource_saturation()
            duration = pressure_duration()
            value["results"]["pressureDuration"] = duration
            mutate(duration)
            with self.subTest(duration=duration), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

        value = evidence()
        value["schemaVersion"] = 7
        value["scenario"]["workloadProfile"] = "step-12"
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_pressure_duration_without_schema_upgrade(self):
        value = evidence()
        value["schemaVersion"] = 6
        value["scenario"]["workloadProfile"] = "step-12"
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        value["results"]["pressureDuration"] = pressure_duration()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_accepts_gc_collection_activity(self):
        value = evidence()
        value["schemaVersion"] = 8
        value["scenario"]["workloadProfile"] = "step-12"
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["postgresPoolSaturation"][
            "threadsAwaitingConnectionMaximum"] = 2
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        value["results"]["pressureDuration"] = pressure_duration()
        value["results"]["gcCollectionActivity"] = gc_collection_activity()
        validate(value, "a" * 40, require_clean=True)

    def test_rejects_invalid_gc_collection_activity(self):
        for mutate in (
            lambda activity: activity.update(samples=23),
            lambda activity: activity.update(metricsUnavailableSamples=1),
            lambda activity: activity.update(collectionsAfter=9),
            lambda activity: activity.update(collectionsDelta=1),
            lambda activity: activity.update(collectionTimeMillisAfter=99),
            lambda activity: activity.update(collectionTimeMillisDelta=24),
            lambda activity: activity.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 8
            value["scenario"]["workloadProfile"] = "step-12"
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            value["results"]["postgresPoolSaturation"][
                "threadsAwaitingConnectionMaximum"] = 2
            value["results"]["eventLoopSaturation"] = event_loop_saturation()
            value["results"]["processResourceSaturation"] = process_resource_saturation()
            value["results"]["pressureDuration"] = pressure_duration()
            activity = gc_collection_activity()
            value["results"]["gcCollectionActivity"] = activity
            mutate(activity)
            with self.subTest(activity=activity), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_accepts_available_and_fully_unavailable_resident_memory(self):
        validate(schema_nine_evidence(), "a" * 40, require_clean=True)

        value = schema_nine_evidence()
        activity = value["results"]["residentMemoryActivity"]
        activity.update(
            metricsUnavailableSamples=24,
            residentBytesBefore=0,
            residentBytesAfter=0,
            residentBytesMaximum=0,
            readFailuresBefore=0,
            readFailuresAfter=0,
            readFailuresDelta=0,
        )
        validate(value, "a" * 40, require_clean=True)

    def test_rejects_invalid_resident_memory_activity(self):
        for mutate in (
            lambda activity: activity.update(samples=23),
            lambda activity: activity.update(configuredRefreshIntervalMillis=249),
            lambda activity: activity.update(metricsUnavailableSamples=25),
            lambda activity: activity.update(residentBytesMaximum=1199),
            lambda activity: activity.update(
                metricsUnavailableSamples=24, residentBytesMaximum=1400),
            lambda activity: activity.update(
                metricsUnavailableSamples=23, residentBytesBefore=0,
                residentBytesAfter=0, residentBytesMaximum=0),
            lambda activity: activity.update(readFailuresAfter=0),
            lambda activity: activity.update(readFailuresDelta=0),
            lambda activity: activity.update(extraField=1),
        ):
            value = schema_nine_evidence()
            activity = value["results"]["residentMemoryActivity"]
            mutate(activity)
            with self.subTest(activity=activity), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_accepts_available_and_fully_unavailable_direct_buffers(self):
        validate(schema_ten_evidence(), "a" * 40, require_clean=True)

        value = schema_ten_evidence()
        activity = value["results"]["directBufferActivity"]
        for name in tuple(activity):
            if name not in ("sampleIntervalMillis", "samples"):
                activity[name] = 24 if name == "metricsUnavailableSamples" else 0
        validate(value, "a" * 40, require_clean=True)

    def test_rejects_invalid_direct_buffer_activity(self):
        for mutate in (
            lambda activity: activity.update(samples=23),
            lambda activity: activity.update(sampleIntervalMillis=10),
            lambda activity: activity.update(metricsUnavailableSamples=25),
            lambda activity: activity.update(bufferCountMaximum=11),
            lambda activity: activity.update(memoryUsedBytesMaximum=1199),
            lambda activity: activity.update(totalCapacityBytesMaximum=1099),
            lambda activity: activity.update(
                metricsUnavailableSamples=24, bufferCountMaximum=14),
            lambda activity: activity.update(extraField=1),
        ):
            value = schema_ten_evidence()
            activity = value["results"]["directBufferActivity"]
            mutate(activity)
            with self.subTest(activity=activity), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_direct_buffers_without_schema_upgrade(self):
        value = schema_nine_evidence()
        value["results"]["directBufferActivity"] = direct_buffer_activity()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_unknown_or_drifting_workload_profile(self):
        for mutate in (
            lambda scenario: scenario.update(workloadProfile="custom"),
            lambda scenario: scenario.update(workloadProfile="step-24"),
            lambda scenario: scenario.update(workloadProfile="step-12",
                                               reconnectBatchSize=6),
        ):
            value = evidence()
            value["schemaVersion"] = 6
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            value["results"]["eventLoopSaturation"] = event_loop_saturation()
            value["results"]["processResourceSaturation"] = process_resource_saturation()
            mutate(value["scenario"])
            with self.subTest(scenario=value["scenario"]), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_saturation_extension_without_schema_upgrade(self):
        value = evidence()
        value["results"]["authenticationSaturation"] = authentication_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_invalid_in_window_saturation(self):
        value = evidence()
        value["schemaVersion"] = 2
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

        for mutate in (
            lambda saturation: saturation.update(sampleIntervalMillis=10),
            lambda saturation: saturation.update(samples=1),
            lambda saturation: saturation.update(activeWorkersMaximum=0),
            lambda saturation: saturation.update(queuedWorkMaximum=13),
            lambda saturation: saturation.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 2
            saturation = {
                "sampleIntervalMillis": 5,
                "samples": 24,
                "activeWorkersMaximum": 3,
                "queuedWorkMaximum": 0,
            }
            value["results"]["authenticationSaturation"] = saturation
            mutate(saturation)
            with self.subTest(saturation=saturation), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_invalid_postgres_pool_saturation(self):
        value = evidence()
        value["schemaVersion"] = 3
        value["results"]["authenticationSaturation"] = authentication_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

        for mutate in (
            lambda saturation: saturation.update(sampleIntervalMillis=10),
            lambda saturation: saturation.update(samples=23),
            lambda saturation: saturation.update(metricsUnavailableSamples=1),
            lambda saturation: saturation.update(activeConnectionsMaximum=0),
            lambda saturation: saturation.update(totalConnectionsMaximum=5),
            lambda saturation: saturation.update(
                threadsAwaitingConnectionMaximum=13),
            lambda saturation: saturation.update(configuredMaximumConnections=8),
            lambda saturation: saturation.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 3
            value["results"]["authenticationSaturation"] = authentication_saturation()
            saturation = postgres_pool_saturation()
            value["results"]["postgresPoolSaturation"] = saturation
            mutate(saturation)
            with self.subTest(saturation=saturation), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_postgres_pool_extension_without_schema_upgrade(self):
        value = evidence()
        value["schemaVersion"] = 2
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_invalid_event_loop_saturation(self):
        value = evidence()
        value["schemaVersion"] = 4
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

        for mutate in (
            lambda saturation: saturation.update(sampleIntervalMillis=10),
            lambda saturation: saturation.update(samples=23),
            lambda saturation: saturation.update(metricsUnavailableSamples=1),
            lambda saturation: saturation.update(workers=2),
            lambda saturation: saturation.update(probeSamplesAfter=40),
            lambda saturation: saturation.update(probeSamplesDelta=27),
            lambda saturation: saturation.update(latestMaximumLagMicros=701),
            lambda saturation: saturation.update(
                sinceStartMaximumLagMicrosAfter=5_000_001),
            lambda saturation: saturation.update(pendingTasksMaximum=100_001),
            lambda saturation: saturation.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 4
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            saturation = event_loop_saturation()
            value["results"]["eventLoopSaturation"] = saturation
            mutate(saturation)
            with self.subTest(saturation=saturation), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_event_loop_extension_without_schema_upgrade(self):
        value = evidence()
        value["schemaVersion"] = 3
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_missing_or_invalid_process_resources(self):
        value = evidence()
        value["schemaVersion"] = 5
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

        for mutate in (
            lambda resource: resource.update(sampleIntervalMillis=10),
            lambda resource: resource.update(samples=23),
            lambda resource: resource.update(cpuTimeUnavailableSamples=1),
            lambda resource: resource.update(cpuTimeMicrosDelta=99_999),
            lambda resource: resource.update(heapUsedBytesMaximum=119),
            lambda resource: resource.update(heapCommittedBytesAfter=119),
            lambda resource: resource.update(heapMaximumBytes=199),
            lambda resource: resource.update(uptimeMillisAfter=10_000),
            lambda resource: resource.update(uptimeMillisDelta=349),
            lambda resource: resource.update(availableProcessors=8),
            lambda resource: resource.update(extraField=1),
        ):
            value = evidence()
            value["schemaVersion"] = 5
            value["results"]["authenticationSaturation"] = authentication_saturation()
            value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
            value["results"]["eventLoopSaturation"] = event_loop_saturation()
            resource = process_resource_saturation()
            value["results"]["processResourceSaturation"] = resource
            mutate(resource)
            with self.subTest(resource=resource), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)

    def test_rejects_process_resource_extension_without_schema_upgrade(self):
        value = evidence()
        value["schemaVersion"] = 4
        value["results"]["authenticationSaturation"] = authentication_saturation()
        value["results"]["postgresPoolSaturation"] = postgres_pool_saturation()
        value["results"]["eventLoopSaturation"] = event_loop_saturation()
        value["results"]["processResourceSaturation"] = process_resource_saturation()
        with self.assertRaises(EvidenceError):
            validate(value, "a" * 40, require_clean=True)

    def test_rejects_topology_reconciliation_and_distribution_errors(self):
        for mutate in (
            lambda value: value["scenario"].update(edgeProcesses=1),
            lambda value: value["scenario"].update(primaryEdgeKilled=False),
            lambda value: value["scenario"].update(connections=17),
            lambda value: value["scenario"].update(reconnectBatchSize=2),
            lambda value: value["results"].update(reconnectErrors=1),
            lambda value: value["results"].update(
                secondaryGatewayAuthenticationAfter=17),
            lambda value: value["results"]["sessionResumeLatencyMicros"].update(p95=5),
        ):
            value = evidence()
            mutate(value)
            with self.subTest(value=value), self.assertRaises(EvidenceError):
                validate(value, "a" * 40, require_clean=True)


if __name__ == "__main__":
    unittest.main()
