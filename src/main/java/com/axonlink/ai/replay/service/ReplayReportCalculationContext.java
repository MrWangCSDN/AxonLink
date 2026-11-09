package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportSummaryView;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Availability is separate from calculated zeroes, including in persisted report summaries. */
public record ReplayReportCalculationContext(Side previous, Side current, List<String> notices) {
    public ReplayReportCalculationContext {
        previous = previous == null ? Side.complete() : previous;
        current = current == null ? Side.complete() : current;
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    public static ReplayReportCalculationContext complete() {
        return new ReplayReportCalculationContext(Side.complete(), Side.complete(), List.of());
    }

    public Map<String, Object> toMap() {
        return Map.of("previous", previous.toMap(), "current", current.toMap(), "notices", notices);
    }

    public static ReplayReportCalculationContext from(ReplayReportSummaryView view) {
        if (view == null || view.totalRow() == null
                || !(view.totalRow().values().get("calculationContext") instanceof Map<?, ?> data)) {
            return complete();
        }
        return new ReplayReportCalculationContext(Side.from(data.get("previous")), Side.from(data.get("current")),
                strings(data.get("notices")));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public record Side(boolean issueStatisticsAvailable, Set<String> noActionUnknownDomains,
                       Set<String> breakdownUnknownDomains, Set<String> matchUnknownDomains,
                       Set<String> legacyRateDomains, boolean rateCalculationAvailable) {
        public Side(boolean issueStatisticsAvailable, Set<String> noActionUnknownDomains,
                    Set<String> breakdownUnknownDomains, Set<String> matchUnknownDomains) {
            this(issueStatisticsAvailable, noActionUnknownDomains, breakdownUnknownDomains, matchUnknownDomains, Set.of(), false);
        }

        public Side(boolean issueStatisticsAvailable, Set<String> noActionUnknownDomains,
                    Set<String> breakdownUnknownDomains, Set<String> matchUnknownDomains, Set<String> legacyRateDomains) {
            this(issueStatisticsAvailable, noActionUnknownDomains, breakdownUnknownDomains, matchUnknownDomains,
                    legacyRateDomains, true);
        }

        public Side {
            noActionUnknownDomains = domains(noActionUnknownDomains);
            breakdownUnknownDomains = domains(breakdownUnknownDomains);
            matchUnknownDomains = domains(matchUnknownDomains);
            legacyRateDomains = domains(legacyRateDomains);
        }

        public static Side complete() {
            return new Side(true, Set.of(), Set.of(), Set.of());
        }

        public boolean hideNoAction(String domain, boolean total) {
            return unknown(noActionUnknownDomains, domain, total);
        }

        public boolean hideBreakdown(String domain, boolean total) {
            return unknown(breakdownUnknownDomains, domain, total);
        }

        public boolean hideMatch(String domain, boolean total) {
            return hideNoAction(domain, total) || unknown(matchUnknownDomains, domain, total);
        }

        public boolean useLegacyRate(String domain, boolean total) {
            return unknown(legacyRateDomains, domain, total);
        }

        public boolean hideSuccessRate(String domain, boolean total) {
            if (rateCalculationAvailable) return false;
            return unknown(noActionUnknownDomains.stream().filter(value -> !legacyRateDomains.contains(value))
                    .collect(Collectors.toSet()), domain, total);
        }

        public boolean hideMatchRate(String domain, boolean total) {
            if (rateCalculationAvailable) return false;
            return hideSuccessRate(domain, total) || unknown(matchUnknownDomains.stream()
                    .filter(value -> !legacyRateDomains.contains(value)).collect(Collectors.toSet()), domain, total);
        }

        private static boolean unknown(Set<String> domains, String domain, boolean total) {
            return total ? !domains.isEmpty() : domains.contains(normalizeDomain(domain));
        }

        private static Set<String> domains(Set<String> values) {
            return values == null ? Set.of() : values.stream().map(Side::normalizeDomain).collect(Collectors.toUnmodifiableSet());
        }

        private static String normalizeDomain(String value) {
            String domain = value == null ? "" : value.trim();
            return domain.startsWith("沙箱")
                    ? "沙箱-" + domain.substring(2).replaceFirst("^[-—_]", "").trim() : domain;
        }

        private Map<String, Object> toMap() {
            return Map.of("issueStatisticsAvailable", issueStatisticsAvailable,
                    "noActionUnknownDomains", noActionUnknownDomains,
                    "breakdownUnknownDomains", breakdownUnknownDomains,
                    "matchUnknownDomains", matchUnknownDomains,
                    "legacyRateDomains", legacyRateDomains,
                    "rateCalculationAvailable", rateCalculationAvailable);
        }

        private static Side from(Object raw) {
            if (!(raw instanceof Map<?, ?> data)) return complete();
            return new Side(!Boolean.FALSE.equals(data.get("issueStatisticsAvailable")),
                    Set.copyOf(strings(data.get("noActionUnknownDomains"))),
                    Set.copyOf(strings(data.get("breakdownUnknownDomains"))),
                    Set.copyOf(strings(data.get("matchUnknownDomains"))),
                    Set.copyOf(strings(data.get("legacyRateDomains"))),
                    Boolean.TRUE.equals(data.get("rateCalculationAvailable")));
        }
    }
}
