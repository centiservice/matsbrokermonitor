package io.mats3.matsbrokermonitor.broadcastreceiver;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsStage;
import io.mats3.MatsStage.StageConfig;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.BrokerInfo;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.BrokerInfoDto;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestinationDto;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.UpdateEvent;

/**
 * @author Endre Stølsvik 2022-11-12 10:34 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsBrokerMonitorBroadcastReceiver implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(MatsBrokerMonitorBroadcastReceiver.class);

    /**
     * Copied from MatsBrokerMonitorBroadcastAndControl
     * <p/>
     * The EndpointId to which the update event is published.
     * <p/>
     * Value is <code>"mats.MatsBrokerMonitorBroadcastUpdate"</code>.
     */
    private static final String BROADCAST_UPDATE_EVENT_TOPIC_ENDPOINT_ID = "mats.MatsBrokerMonitorBroadcastUpdate";

    /**
     * Copied from MatsInterceptable.MatsLoggingInterceptor.
     * <p/>
     * We accept that this won't be logged, as it will be annoying noise without much merit.
     */
    private static final String SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY = "mats.SuppressLoggingAllowed";

    /**
     * Copied from MatsBrokerMonitorBroadcastAndControl
     * <p/>
     * The EndpointId to which one may request operations, currently forceUpdate.
     * <p/>
     * Value is <code>"mats.MatsBrokerMonitorControl"</code>.
     */
    public static final String MATSBROKERMONITOR_CONTROL = "mats.MatsBrokerMonitorControl";

    private MatsEndpoint<Void, Void> _broadcastReceiver;

    private MatsFactory _matsFactory;

    private final CopyOnWriteArrayList<Consumer<UpdateEvent>> _listeners = new CopyOnWriteArrayList<>();

    public static MatsBrokerMonitorBroadcastReceiver install(MatsFactory matsFactory) {
        return new MatsBrokerMonitorBroadcastReceiver(matsFactory);
    }

    public void forceUpdate(String correlationId, boolean full) {
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init
                .traceId("MatsBrokerMonitorBroadcastReceiver.forceUpdate:"
                        + Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36))
                .from("MatsBrokerMonitorBroadcastReceiver.forceUpdate")
                .to(MATSBROKERMONITOR_CONTROL)
                .send(MatsBrokerMonitorCommandDto.forceUpdate(correlationId, full)));
    }

    public void registerListener(Consumer<UpdateEvent> listener) {
        _listeners.add(listener);
    }

    public void removeListener(Consumer<UpdateEvent> listener) {
        _listeners.remove(listener);
    }

    @Override
    public void close() {
        // Remove the SubscriptionTerminator Endpoint from MatsFactory, just to be clean.
        _broadcastReceiver.remove(5_000);
        // Null out Receiver, just to be clean (help GC)
        _broadcastReceiver = null;
        // Null out MatsFactory, just to be clean (help GC)
        _matsFactory = null;
        // No more listeners
        _listeners.clear();
    }

    private MatsBrokerMonitorBroadcastReceiver(MatsFactory matsFactory) {
        _matsFactory = matsFactory;
        // :: Create the SubscriptionTerminator receiving the broadcast from MatsBrokerMonitorBroadcastAndControl
        _broadcastReceiver = matsFactory.subscriptionTerminator(BROADCAST_UPDATE_EVENT_TOPIC_ENDPOINT_ID, void.class,
                BroadcastUpdateEventDto.class, (ctx, state, updateEvent) -> {
                    NavigableMap<String, MatsBrokerDestination> eventDestinations = updateEvent.getEventDestinations();
                    if (log.isTraceEnabled()) log.info("Received Update: FullUpdate:[" + updateEvent.isFullUpdate()
                            + "], CorrelationId:[" + updateEvent.getCorrelationId()
                            + "], # of destinations:[" + eventDestinations.size() + "].");

                    // :: Move the Queues/Topics, and all DLQs, over to handy maps keyed on stageId
                    Map<String, MatsBrokerDestination> destMap = new HashMap<>();
                    Map<String, MatsBrokerDestination> dlqMap = new HashMap<>();
                    for (MatsBrokerDestination dest : eventDestinations.values()) {
                        if (dest.getMatsStageId().isPresent()) {
                            if (dest.isDlq()) {
                                dlqMap.put(dest.getMatsStageId().get(), dest);
                                if (log.isTraceEnabled())
                                    log.trace(" \\- Received DLQ info [" + dest.getNumberOfQueuedMessages()
                                            + " msgs] for stage [" + dest.getMatsStageId().get() + "]");
                            }
                            else {
                                destMap.put(dest.getMatsStageId().get(), dest);
                                if (log.isTraceEnabled())
                                    log.trace(" \\- Received Destination info [" + dest.getNumberOfQueuedMessages()
                                            + " msgs] for stage [" + dest.getMatsStageId().get() + "]");
                            }
                        }
                    }

                    // :: For all Stages for all Endpoints in the MatsFactory, add or remove Queue/Topic and DLQ as
                    // attribute on the StageConfig, using above maps.
                    List<MatsEndpoint<?, ?>> endpoints = matsFactory.getEndpoints();
                    for (MatsEndpoint<?, ?> endpoint : endpoints) {
                        List<? extends MatsStage<?, ?, ?>> stages = endpoint.getStages();
                        for (MatsStage<?, ?, ?> stage : stages) {
                            StageConfig<?, ?, ?> stageConfig = stage.getStageConfig();
                            String stageId = stageConfig.getStageId();
                            // If destMap returns null, that is what we want to set.
                            stageConfig.setAttribute(MatsBrokerDestination.STAGE_ATTRIBUTE_DESTINATION,
                                    destMap.get(stageId));
                            // If dlqMap returns null, that is what we want to set.
                            stageConfig.setAttribute(MatsBrokerDestination.STAGE_ATTRIBUTE_DLQ,
                                    dlqMap.get(stageId));
                        }
                    }

                    // :: Notify any listeners.
                    for (Consumer<UpdateEvent> listener : _listeners) {
                        try {
                            listener.accept(updateEvent);
                        }
                        catch (Throwable t) {
                            log.error("The listener of class [" + listener.getClass().getName()
                                    + "] threw when being invoked."
                                    + " Ignoring.", t);
                        }
                    }
                });
        // :: We don't want this to be logged, as it will be annoying noise without much merit.
        // Allow for log suppression for this SubscriptionTerminator
        _broadcastReceiver.getEndpointConfig().setAttribute(SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY,
                Boolean.TRUE);
    }

    /**
     * NOTE: THIS IS COPIED OVER from MatsBrokerMonitorBroadcastAndControl.
     * <p/>
     * UpdateEvent DTO from MatsBrokerMonitor, which is sent to SubscriptionTerminator ("topic") EndpointId
     * {@link #BROADCAST_UPDATE_EVENT_TOPIC_ENDPOINT_ID}.
     */
    private static class BroadcastUpdateEventDto implements UpdateEvent {
        private long suts;
        private String cid; // nullable
        private boolean fu;
        private BrokerInfoDto bi; // nullable
        private List<MatsBrokerDestinationDto> ds;

        public BroadcastUpdateEventDto() {
            /* need no-args constructor for deserializing with Jackson */
        }

        public static BroadcastUpdateEventDto of(UpdateEvent updateEvent) {
            NavigableMap<String, MatsBrokerDestination> eventDestinations = updateEvent.getEventDestinations();

            ArrayList<MatsBrokerDestinationDto> destinationDtos = new ArrayList<>(eventDestinations.size());
            for (MatsBrokerDestination dest : eventDestinations.values()) {
                destinationDtos.add(MatsBrokerDestinationDto.of(dest));
            }

            BrokerInfoDto brokerInfoDto = null;
            if (updateEvent.getBrokerInfo().isPresent()) {
                brokerInfoDto = BrokerInfoDto.of(updateEvent.getBrokerInfo().get());
            }

            return new BroadcastUpdateEventDto(updateEvent.isFullUpdate(),
                    updateEvent.getStatisticsUpdateMillis(),
                    updateEvent.getCorrelationId().orElse(null),
                    brokerInfoDto,
                    destinationDtos);
        }

        public BroadcastUpdateEventDto(boolean fullUpdate, long statisticsUpdateMillis,
                String correlationId, BrokerInfoDto brokerInfo,
                List<MatsBrokerDestinationDto> destinations) {
            this.suts = statisticsUpdateMillis;
            this.fu = fullUpdate;
            this.cid = correlationId;
            this.bi = brokerInfo;
            this.ds = destinations;
        }

        public boolean isFullUpdate() {
            return fu;
        }

        @Override
        public boolean isUpdateEventOriginatedOnThisNode() {
            return false;
        }

        @Override
        public Optional<BrokerInfo> getBrokerInfo() {
            return Optional.ofNullable(bi);
        }

        @Override
        public long getStatisticsUpdateMillis() {
            return suts;
        }

        public Optional<String> getCorrelationId() {
            return Optional.ofNullable(cid);
        }

        @Override
        public NavigableMap<String, MatsBrokerDestination> getEventDestinations() {
            TreeMap<String, MatsBrokerDestination> ret = new TreeMap<>();
            for (MatsBrokerDestination dest : ds) {
                ret.put(dest.getFqDestinationName(), dest);
            }
            return ret;
        }

        @Override
        public String toString() {
            return "UpdateEventDto{" +
                    "statisticsUpdateMillis=" + suts +
                    ", correlationId='" + cid + '\'' +
                    ", isFullUpdate=" + fu +
                    ", brokerInfo=" + bi +
                    ", eventDestinations=" + ds.size() +
                    '}';
        }
    }

    /**
     * NOTE: THIS IS COPIED OVER from MatsBrokerMonitorBroadcastAndControl.
     * <p/>
     * "Command object", which can be sent to Endpoint {@link #MATSBROKERMONITOR_CONTROL}.
     */
    private static class MatsBrokerMonitorCommandDto {
        /**
         * Can be {@link #FORCE_UPDATE} or {@link #FORCE_UPDATE_FULL}
         */
        private String m;

        private String cid;

        public static String FORCE_UPDATE = "forceUpdate";
        public static String FORCE_UPDATE_FULL = "forceUpdateFull";

        public static MatsBrokerMonitorCommandDto forceUpdate(String correlationId, boolean full) {
            return new MatsBrokerMonitorCommandDto(correlationId, full ? FORCE_UPDATE_FULL : FORCE_UPDATE);
        }

        private MatsBrokerMonitorCommandDto() {
            /* need no-args constructor for deserializing with Jackson */
        }

        private MatsBrokerMonitorCommandDto(String correlationId, String method) {
            this.m = method;
            this.cid = correlationId;
        }

        public String getMethod() {
            return m;
        }

        public String getCorrelationId() {
            return cid;
        }
    }
}
