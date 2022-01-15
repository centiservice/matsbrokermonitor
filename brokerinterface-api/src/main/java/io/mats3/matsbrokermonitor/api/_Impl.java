package io.mats3.matsbrokermonitor.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsEndpointBrokerRepresentation;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsEndpointGroupBrokerRepresentation;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsStageBrokerRepresentation;

/**
 * @author Endre Stølsvik 2022-01-09 00:23 - http://stolsvik.com/, endre@stolsvik.com
 */
public final class _Impl {

    private _Impl() {
        /* hide constructor */
    }

    private static final Pattern STAGE_PATTERN = Pattern.compile("(.*)\\.stage(\\d+)");

    static MatsFabricBrokerRepresentation stack_interal(Collection<MatsBrokerDestination> matsBrokerDestinations) {
        MatsBrokerDestination globalDlq = null;
        SortedMap<String, MatsEndpointBrokerRepresentationImpl> endpointBrokerRepresentations = new TreeMap<>();
        for (MatsBrokerDestination matsBrokerDestination : matsBrokerDestinations) {
            // ?: Is this the Global DLQ?
            if (matsBrokerDestination.isGlobalDlq()) {
                // -> Yes, Global DLQ: save, and continue.
                globalDlq = matsBrokerDestination;
                continue;
            }
            // ?: Is there a Mats StageId (also EndpointId)?
            if (!matsBrokerDestination.getMatsStageId().isPresent()) {
                // -> No, no StageId, so ditch this, and continue.
                continue;
            }
            // :: Find which Endpoint this queue/topic relates to,
            String stageId = matsBrokerDestination.getMatsStageId().get();
            String endpointId;
            int stageIndex;
            Matcher stageMatcher = STAGE_PATTERN.matcher(stageId);
            if (stageMatcher.matches()) {
                // -> Stage
                endpointId = stageMatcher.group(1);
                stageIndex = Integer.parseInt(stageMatcher.group(2));
            }
            else {
                // -> Missing "stageXX", so this is the "stage0" for the Endpoint, where StageId == EndpointId
                endpointId = stageId;
                stageIndex = 0;
            }

            // Endpoint: Create the MatsEndpointBrokerRepresentation if not already present.
            MatsEndpointBrokerRepresentationImpl matsEndpointBrokerRepresentation = endpointBrokerRepresentations
                    .computeIfAbsent(endpointId, MatsEndpointBrokerRepresentationImpl::new);

            // Stage: Create the MatsStageBrokerRepresentation if not already present.
            MatsStageBrokerRepresentationImpl matsStageBrokerRepresentation = matsEndpointBrokerRepresentation._stages
                    .computeIfAbsent(stageIndex, stIdx -> new MatsStageBrokerRepresentationImpl(stIdx, stageId));

            // Add the MatsBrokerDestination to the Stage, either it is the incoming destination or the DLQ.
            if (matsBrokerDestination.isDlq()) {
                matsStageBrokerRepresentation._dlqDestination = matsBrokerDestination;
            }
            else {
                matsStageBrokerRepresentation._incomingDestination = matsBrokerDestination;
            }
        }

        // :: Stack up into "EndpointGroups"
        TreeMap<String, TreeMap<String, MatsEndpointBrokerRepresentation>> services = endpointBrokerRepresentations
                .values().stream()
                .map(e -> (MatsEndpointBrokerRepresentation) e)
                .collect(Collectors.groupingBy(e -> {
                    // :: Get "ServiceName", up to first dot - or entire name if no dots.
                    String endpointId = e.getEndpointId();
                    int dot = endpointId.indexOf('.');
                    return dot != -1 ? endpointId.substring(0, dot) : endpointId;
                }, TreeMap::new, Collectors.toMap(MatsEndpointBrokerRepresentation::getEndpointId, e -> e,
                        (ep1, ep2) -> {
                            throw new IllegalStateException("Collision! [" + ep1 + "], [" + ep2 + "]");
                        }, TreeMap::new)));

        TreeMap<String, MatsEndpointGroupBrokerRepresentation> matsServiceBrokerRepresentations = services.entrySet().stream()
                .collect(Collectors.toMap(Entry::getKey,
                        entry -> new MatsEndpointGroupBrokerRepresentationImpl(entry.getKey(), entry.getValue()),
                        (sg1, sg2) -> {
                            throw new IllegalStateException("Collision! [" + sg1 + "], [" + sg2 + "]");
                        }, TreeMap::new));

        return new MatsFabricBrokerRepresentationImpl(globalDlq,
                matsServiceBrokerRepresentations,
                endpointBrokerRepresentations);
    }

    static class MatsFabricBrokerRepresentationImpl implements MatsFabricBrokerRepresentation {
        private final MatsBrokerDestination _globalDlq;
        private final SortedMap<String, ? extends MatsEndpointGroupBrokerRepresentation> _matsServiceBrokerRepresentations;
        private final SortedMap<String, ? extends MatsEndpointBrokerRepresentation> _matsEndpointBrokerRepresentations;

        public MatsFabricBrokerRepresentationImpl(MatsBrokerDestination globalDlq,
                SortedMap<String, ? extends MatsEndpointGroupBrokerRepresentation> matsServiceBrokerRepresentations,
                SortedMap<String, ? extends MatsEndpointBrokerRepresentation> matsEndpointBrokerRepresentations) {
            _matsServiceBrokerRepresentations = matsServiceBrokerRepresentations;
            _matsEndpointBrokerRepresentations = matsEndpointBrokerRepresentations;
            _globalDlq = globalDlq;
        }

        @Override
        public Optional<MatsBrokerDestination> getGlobalDlq() {
            return Optional.ofNullable(_globalDlq);
        }

        @Override
        public SortedMap<String, MatsEndpointGroupBrokerRepresentation> getMatsEndpointGroupBrokerRepresentations() {
            return Collections.unmodifiableSortedMap(_matsServiceBrokerRepresentations);
        }

        @Override
        public SortedMap<String, MatsEndpointBrokerRepresentation> getMatsEndpointBrokerRepresentations() {
            return Collections.unmodifiableSortedMap(_matsEndpointBrokerRepresentations);
        }
    }

    static class MatsEndpointGroupBrokerRepresentationImpl implements MatsEndpointGroupBrokerRepresentation {
        private final String _serviceName;
        private final SortedMap<String, MatsEndpointBrokerRepresentation> _matsEndpointBrokerRepresentations;

        public MatsEndpointGroupBrokerRepresentationImpl(String serviceName,
                SortedMap<String, MatsEndpointBrokerRepresentation> matsEndpointBrokerRepresentations) {
            _serviceName = serviceName;
            _matsEndpointBrokerRepresentations = matsEndpointBrokerRepresentations;
        }

        @Override
        public String getEndpointGroup() {
            return _serviceName;
        }

        @Override
        public SortedMap<String, MatsEndpointBrokerRepresentation> getMatsEndpointBrokerRepresentations() {
            return Collections.unmodifiableSortedMap(_matsEndpointBrokerRepresentations);
        }
    }

    static class MatsEndpointBrokerRepresentationImpl implements MatsEndpointBrokerRepresentation {

        private final String _endpointId;
        private final SortedMap<Integer, MatsStageBrokerRepresentationImpl> _stages = new TreeMap<>();

        public MatsEndpointBrokerRepresentationImpl(String endpointId) {
            _endpointId = endpointId;
        }

        @Override
        public String getEndpointId() {
            return _endpointId;
        }

        @Override
        public SortedMap<Integer, MatsStageBrokerRepresentation> getStages() {
            return Collections.unmodifiableSortedMap(_stages);
        }
    }

    static class MatsStageBrokerRepresentationImpl implements MatsStageBrokerRepresentation {
        private final int _stageIndex;
        private final String _stageId;

        private MatsBrokerDestination _incomingDestination;
        private MatsBrokerDestination _dlqDestination;

        public MatsStageBrokerRepresentationImpl(int stageIndex, String stageId) {
            _stageIndex = stageIndex;
            _stageId = stageId;
        }

        @Override
        public int getStageIndex() {
            return _stageIndex;
        }

        @Override
        public String getStageId() {
            return _stageId;
        }

        @Override
        public Optional<MatsBrokerDestination> getIncomingDestination() {
            return Optional.ofNullable(_incomingDestination);
        }

        @Override
        public Optional<MatsBrokerDestination> getDlqDestination() {
            return Optional.ofNullable(_dlqDestination);
        }
    }
}
