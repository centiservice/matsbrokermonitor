package io.mats3.matsbrokermonitor.htmlgui.impl;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.BrokerInfo;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.BrokerSnapshot;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.DestinationType;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsEndpointBrokerRepresentation;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsEndpointGroupBrokerRepresentation;
import io.mats3.matsbrokermonitor.api.MatsFabricBrokerRepresentation.MatsStageBrokerRepresentation;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui.AccessControl;

/**
 * @author Endre Stølsvik 2022-03-13 23:32 - http://stolsvik.com/, endre@stolsvik.com
 */
class BrokerOverview {
    static void gui_BrokerOverview(MatsBrokerMonitor matsBrokerMonitor, Outputter out,
            Map<String, String[]> requestParameters, AccessControl ac)
            throws IOException {
        out.html("<div id='matsbm_page_broker_overview' class='matsbm_report'>\n");
        out.html("<div class='matsbm_heading'>");
        Optional<BrokerInfo> brokerInfoO = matsBrokerMonitor.getBrokerInfo();
        if (brokerInfoO.isPresent()) {
            BrokerInfo brokerInfo = brokerInfoO.get();
            out.html("Broker <h1>'").DATA(brokerInfo.getBrokerName()).html("'</h1>");
            out.html("   of type ").DATA(brokerInfo.getBrokerType());
        }
        else {
            out.html("<h2>Unknown broker</h2>");
        }
        out.html("</div>\n");

        Optional<BrokerSnapshot> snapshotO = matsBrokerMonitor.getSnapshot();
        if (!snapshotO.isPresent()) {
            out.html("<h1>Have not gotten an update from the broker yet!</h1>");
            return;
        }

        BrokerSnapshot snapshot = snapshotO.get();

        boolean tooOldMessage = false;
        boolean hasDlqMessage = false;

        MatsFabricBrokerRepresentation stack = MatsFabricBrokerRepresentation
                .stack(snapshot.getMatsDestinations().values());

        out.html("Updated as of: <b>").DATA(Statics.formatTimestampSpan(snapshot.getLastUpdateLocalMillis()));
        if (snapshot.getLastUpdateBrokerMillis().isPresent()) {
            out.html("</b> - broker time: <b>")
                    .DATA(Statics.formatTimestamp(snapshot.getLastUpdateBrokerMillis().getAsLong()));
        }
        out.html("</b> <i>(all ages on queues are wrt. this update time)</i><br>");

        long totalNumberOfIncomingMessages = stack.getTotalNumberOfIncomingMessages();
        out.html("Total queued messages: <b>").DATA(totalNumberOfIncomingMessages).html("</b>");
        if (totalNumberOfIncomingMessages > 0) {
            long maxStage = stack.getMaxStageNumberOfIncomingMessages();
            out.html(", worst queue has <b>").DATA(maxStage).html("</b> message").html(maxStage > 1 ? "s" : "");
        }
        OptionalLong oldestIncomingO = stack.getOldestIncomingMessageAgeMillis();
        if (oldestIncomingO.isPresent()) {
            long oldestIncoming = oldestIncomingO.getAsLong();
            tooOldMessage = (oldestIncoming > 10 * 60 * 1000);
            out.html(", ").html(tooOldMessage ? "<span class='matsbm_messages_old'>" : "")
                    .html("oldest message is ")
                    .html("<b>").DATA(Statics.millisSpanToHuman(oldestIncoming)).html("</b> old.")
                    .html(tooOldMessage ? "</span>" : "");
        }
        out.html("<br>\n");
        long totalNumberOfDeadLetterMessages = stack.getTotalNumberOfDeadLetterMessages();
        out.html("Total DLQed messages: <b>").DATA(totalNumberOfDeadLetterMessages).html("</b>");
        if (totalNumberOfDeadLetterMessages > 0) {
            hasDlqMessage = true;
            long maxQueue = stack.getMaxQueueNumberOfDeadLetterMessages();
            out.html(", worst DLQ has <b>").DATA(maxQueue).html("</b> message").html(maxQueue > 1 ? "s" : "");
        }
        OptionalLong oldestDlq = stack.getOldestDlqMessageAgeMillis();
        if (oldestDlq.isPresent()) {
            out.html(", oldest DLQ message is <b>").DATA(Statics.millisSpanToHuman(oldestDlq.getAsLong()))
                    .html("</b> old.");
        }
        out.html("<br>\n<br>\n");

        // :: ToC
        out.html("<b>EndpointGroups ToC</b><br>\n");
        for (MatsEndpointGroupBrokerRepresentation endpointGroup : stack.getEndpointGroups()
                .values()) {
            String endpointGroupId = endpointGroup.getEndpointGroup().trim().isEmpty()
                    ? "{empty string}"
                    : endpointGroup.getEndpointGroup();
            out.html("&nbsp;&nbsp;<a href='#").DATA(endpointGroupId).html("'>")
                    .DATA(endpointGroupId)
                    .html("</a>");
            out.html("<br>\n");
        }
        out.html("<br>\n");

        // :: Global DLQ
        if (stack.getDefaultGlobalDlq().isPresent()) {
            out.html("<div class='matsbm_endpoint_group'>\n");
            out.html("<h2>Global DLQ</h2><br>");
            MatsBrokerDestination globalDlq = stack.getDefaultGlobalDlq().get();
            out.html("<table class='matsbm_table_endpointgroup'>");
            out.html("<tr><td>");
            out.html("<div class='matsbm_epid matsbm_epid_queue'>")
                    .DATA(globalDlq.getDestinationName())
                    .html("</div>");
            out.html("</td><td><div class='matsbm_label matsbm_label_queue'>Queue</div></td>");

            out.html("<td>");
            out.html("<div class='matsbm_stage'>")
                    .DATA(globalDlq.getFqDestinationName());
            out_queueCount(out, globalDlq);
            out.html("</div>");
            out.html("</td>");
            out.html("</table>");

            out.html("</div>");
        }

        // :: Foreach EndpointGroup
        for (MatsEndpointGroupBrokerRepresentation service : stack.getEndpointGroups()
                .values()) {
            // :: EndpointGroup
            String endpointGroupId = service.getEndpointGroup().trim().isEmpty()
                    ? "{empty string}"
                    : service.getEndpointGroup();
            out.html("<div class='matsbm_endpoint_group' id='").DATA(endpointGroupId).html("'>\n");
            out.html("<a href='#").DATA(endpointGroupId).html("'>");
            out.html("<h2>").DATA(endpointGroupId).html("</h2></a><br>\n");

            // :: Foreach Endpoint
            out.html("<table class='matsbm_table_endpointgroup'>");
            for (MatsEndpointBrokerRepresentation endpoint : service.getEndpoints().values()) {
                out.html("<tr>");
                String endpointId = endpoint.getEndpointId();
                Map<Integer, MatsStageBrokerRepresentation> stages = endpoint.getStages();

                // :: Find whether endpoint is a queue or topic.
                // There will always be at least one stage, otherwise the endpoint wouldn't be defined.
                MatsStageBrokerRepresentation first = stages.values().iterator().next();
                // There will either be an incoming, or a DLQ, otherwise the stage wouldn't be defined.
                MatsBrokerDestination firstDestinationOrDlq = first.getIncomingDestination()
                        .orElseGet(() -> first.getDlqDestination()
                                .orElseThrow(() -> new AssertionError("Missing both Incoming and DLQ destinations!")));

                boolean privateEp = endpointId.contains(".private.");
                boolean queue = firstDestinationOrDlq.getDestinationType() == DestinationType.QUEUE;

                out.html("<td><div class='matsbm_epid matsbm_epid")
                        .html(queue ? "_queue" : "_topic")
                        .html(privateEp ? "_private" : "")
                        .html("'>")
                        .DATA(endpointId).html("</div></td>");

                out.html("<td><div class='matsbm_label matsbm_label")
                        .html(queue ? "_queue" : "_topic")
                        .html(privateEp ? "_private" : "")
                        .html("'>")
                        .DATA(queue ? "Queue" : "Topic").html("</div></td>");

                // :: Foreach Stage
                out.html("<td>");
                boolean single = (stages.size() == 1) && (stages.values().iterator().next().getStageIndex() == 0);
                for (MatsStageBrokerRepresentation stage : stages.values()) {
                    boolean initial = stage.getStageIndex() == 0;
                    out.html("<div class='matsbm_stage'>");
                    out.html(initial
                            ? ("<div class='matsbm_stage_initial'>" + (single ? "single" : "initial") + "</div>")
                            : "S" + stage.getStageIndex());
                    Optional<MatsBrokerDestination> incomingDest = stage.getIncomingDestination();
                    if (incomingDest.isPresent()) {
                        out_queueCount(out, incomingDest.get());
                    }

                    Optional<MatsBrokerDestination> dlqDest = stage.getDlqDestination();
                    if (dlqDest.isPresent()) {
                        out_queueCount(out, dlqDest.get());
                    }
                    out.html("</div>"); // /matsbm_stage
                }
                out.html("</td>");
                out.html("</tr>\n");
            }
            out.html("</table>\n");
            out.html("</div>\n");
        }
        out.html("</div>\n");
    }

    private static void out_queueCount(Outputter out, MatsBrokerDestination destination) throws IOException {
        if (destination.getDestinationType() == DestinationType.QUEUE) {
            // -> Queue
            String style = destination.isDlq()
                    ? destination.getNumberOfQueuedMessages() == 0 ? "dlq_zero" : "dlq"
                    : destination.getNumberOfQueuedMessages() == 0 ? "queue_zero" : "queue";
            out.html("<a class='").html(style).html("' href='?browse&destinationId=")
                    .html("queue:")
                    .DATA(destination.getDestinationName())
                    .html("'>");
        }
        else {
            // -> Topic
            out.html("<div class='topic'>");
        }
        out.html(destination.isDlq() ? "DLQ:" : "")
                .DATA(destination.getNumberOfQueuedMessages());
        out.html(destination.getDestinationType() == DestinationType.QUEUE ? "</a>" : "</div>");

        long age = destination.getHeadMessageAgeMillis().orElse(0);
        if (age > 0) {
            out.html("<div class='matsbm_age'>(").DATA(Statics.millisSpanToHuman(age)).html(")</div>");
        }
    }
}
