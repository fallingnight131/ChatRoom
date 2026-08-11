package com.fallingnight.chat.gateway.transport;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves direct or sanitized X-Forwarded-For peers without hostname lookup. */
public final class TrustedProxyPolicy {
    private static final int MAX_HEADER_BYTES = 512;
    private final List<IpSubnet> trustedProxies;
    private final int maxForwardedHops;

    private TrustedProxyPolicy(List<IpSubnet> trustedProxies, int maxForwardedHops) {
        this.trustedProxies = List.copyOf(trustedProxies);
        this.maxForwardedHops = maxForwardedHops;
    }

    public static TrustedProxyPolicy directOnly() {
        return new TrustedProxyPolicy(List.of(), 1);
    }

    public static TrustedProxyPolicy trusted(
            List<String> trustedProxyCidrs,
            int maxForwardedHops) {
        Objects.requireNonNull(trustedProxyCidrs, "trustedProxyCidrs");
        if (trustedProxyCidrs.isEmpty() || trustedProxyCidrs.size() > 32) {
            throw new IllegalArgumentException("trusted proxy CIDR count must be 1..32");
        }
        if (maxForwardedHops < 1 || maxForwardedHops > 16) {
            throw new IllegalArgumentException("forwarded hop limit must be 1..16");
        }
        return new TrustedProxyPolicy(
                trustedProxyCidrs.stream().map(IpSubnet::parse).toList(),
                maxForwardedHops);
    }

    public PeerResolution resolve(
            InetSocketAddress directPeer,
            List<String> xForwardedForValues) {
        List<String> forwarded = xForwardedForValues == null
                ? List.of()
                : new ArrayList<>(xForwardedForValues);
        InetAddress direct = resolvedAddress(directPeer);
        if (direct == null) {
            return PeerResolution.rejected(
                    PeerResolutionDecision.REJECTED_MISSING_DIRECT_PEER);
        }
        boolean directTrusted = isTrusted(direct);
        if (!directTrusted) {
            return PeerResolution.accepted(
                    direct.getHostAddress(),
                    forwarded.isEmpty()
                            ? PeerResolutionDecision.DIRECT
                            : PeerResolutionDecision.DIRECT_FORWARDING_IGNORED);
        }
        if (forwarded.isEmpty()) {
            return PeerResolution.rejected(
                    PeerResolutionDecision.REJECTED_MISSING_FORWARDING);
        }

        List<InetAddress> chain = parseForwardedChain(forwarded);
        if (chain.isEmpty()) {
            return PeerResolution.rejected(
                    PeerResolutionDecision.REJECTED_INVALID_FORWARDING);
        }
        InetAddress selected = direct;
        for (int index = chain.size() - 1; index >= 0; index--) {
            if (!isTrusted(selected)) {
                break;
            }
            selected = chain.get(index);
        }
        return PeerResolution.accepted(
                selected.getHostAddress(), PeerResolutionDecision.TRUSTED_FORWARDING);
    }

    private List<InetAddress> parseForwardedChain(List<String> values) {
        if (values.size() > 4) {
            return List.of();
        }
        int bytes = 0;
        List<InetAddress> chain = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                return List.of();
            }
            bytes += value.getBytes(java.nio.charset.StandardCharsets.US_ASCII).length;
            if (bytes > MAX_HEADER_BYTES) {
                return List.of();
            }
            for (String token : value.split(",", -1)) {
                InetAddress address = literalAddress(token.trim());
                if (address == null || chain.size() >= maxForwardedHops) {
                    return List.of();
                }
                chain.add(address);
            }
        }
        return List.copyOf(chain);
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(subnet -> subnet.contains(address));
    }

    private static InetAddress resolvedAddress(InetSocketAddress address) {
        return address == null ? null : address.getAddress();
    }

    private static InetAddress literalAddress(String value) {
        if (isIpv4Literal(value)) {
            return numericAddress(value, Inet4Address.class);
        }
        if (value.contains(":") && value.matches("[0-9a-fA-F:]+")) {
            return numericAddress(value, Inet6Address.class);
        }
        return null;
    }

    private static boolean isIpv4Literal(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress numericAddress(
            String value,
            Class<? extends InetAddress> expectedType) {
        try {
            InetAddress address = InetAddress.getByName(value);
            return expectedType.isInstance(address) ? address : null;
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private record IpSubnet(byte[] network, int prefixBits) {
        static IpSubnet parse(String value) {
            Objects.requireNonNull(value, "trusted proxy CIDR");
            String[] parts = value.split("/", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("trusted proxy must use CIDR notation");
            }
            InetAddress address = literalAddress(parts[0]);
            if (address == null || !parts[1].matches("0|[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException("trusted proxy CIDR is invalid");
            }
            int prefix = Integer.parseInt(parts[1]);
            byte[] bytes = address.getAddress();
            if (prefix > bytes.length * Byte.SIZE) {
                throw new IllegalArgumentException("trusted proxy prefix is invalid");
            }
            byte[] masked = bytes.clone();
            mask(masked, prefix);
            return new IpSubnet(masked, prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            mask(bytes, prefixBits);
            return java.util.Arrays.equals(network, bytes);
        }

        private static void mask(byte[] bytes, int prefix) {
            for (int bit = prefix; bit < bytes.length * Byte.SIZE; bit++) {
                int index = bit / Byte.SIZE;
                int mask = ~(1 << (7 - (bit % Byte.SIZE)));
                bytes[index] = (byte) (bytes[index] & mask);
            }
        }
    }
}
