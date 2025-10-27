package io.mats3.matsbrokermonitor.htmlgui.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions;
import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions.MatsBrokerMessageMetadata;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.UpdateEvent;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui;
import io.mats3.serial.MatsSerializer;
import io.mats3.util.FieldBasedJacksonMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * Instantiate a <b>singleton</b> of this class, supplying it a {@link MatsBrokerMonitor} instance, <b>to which this
 * instance will register as listener</b>. Again: You are NOT supposed to instantiate an instance of this class per
 * rendering, as this instance is "active" and will register as listener and may instantiate threads.
 *
 * @author Endre Stølsvik 2021-12-17 10:22 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsBrokerMonitorHtmlGuiImpl implements MatsBrokerMonitorHtmlGui, Statics {
    private final Logger log = LoggerFactory.getLogger(MatsBrokerMonitorHtmlGuiImpl.class);

    private final MatsBrokerMonitor _matsBrokerMonitor;
    private final MatsBrokerBrowseAndActions _matsBrokerBrowseAndActions;
    private final List<? super MonitorAddition> _monitorAdditions;
    private final MatsSerializer _matsSerializer;

    /**
     * <b>DO NOT USE THIS CONSTRUCTOR</b>, use factories on {@link MatsBrokerMonitorHtmlGui}!
     */
    public MatsBrokerMonitorHtmlGuiImpl(MatsBrokerMonitor matsBrokerMonitor,
            MatsBrokerBrowseAndActions matsBrokerBrowseAndActions,
            List<? super MonitorAddition> monitorAdditions,
            MatsSerializer matsSerializer) {
        _matsBrokerMonitor = matsBrokerMonitor;
        _matsBrokerBrowseAndActions = matsBrokerBrowseAndActions;
        _monitorAdditions = monitorAdditions == null ? Collections.emptyList() : monitorAdditions;
        _matsSerializer = matsSerializer;

        _matsBrokerMonitor.registerListener(new UpdateEventListener());
    }

    private final ConcurrentHashMap<String, CountDownLatch> _updateEventWaiters = new ConcurrentHashMap<>();

    private class UpdateEventListener implements Consumer<UpdateEvent> {
        @Override
        public void accept(UpdateEvent updateEvent) {
            if (updateEvent.getCorrelationId().isPresent()) {
                CountDownLatch waitingLatch = _updateEventWaiters.get(updateEvent.getCorrelationId().get());
                if (waitingLatch != null) {
                    log.info("Got update event, found waiter for: " + updateEvent);
                    waitingLatch.countDown();
                }
            }
        }
    }

    private String _jsonUrlPath = null;

    public void setJsonUrlPath(String jsonUrlPath) {
        _jsonUrlPath = jsonUrlPath;
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page.
     */
    public void outputStyleSheet(Appendable out) throws IOException {
        includeFile(out, "matsbrokermonitor.css");
    }

    /**
     * Note: The return from this method is static, and should only be included once per HTML page.
     */
    public void outputJavaScript(Appendable out) throws IOException {
        includeFile(out, "matsbrokermonitor.js");
    }

    private static void includeFile(Appendable out, String file) throws IOException {
        String filename = MatsBrokerMonitorHtmlGuiImpl.class.getPackage().getName().replace('.', '/') + '/' + file;
        InputStream is = MatsBrokerMonitorHtmlGuiImpl.class.getClassLoader().getResourceAsStream(filename);
        if (is == null) {
            throw new IllegalStateException("Missing '" + file + "' from ClassLoader.");
        }
        InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(isr);
        while (true) {
            String line = br.readLine();
            if (line == null) {
                break;
            }
            out.append(line).append('\n');
        }
    }

    @Override
    public void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac)
            throws IOException {
        long nanosAsStart_fullRender = System.nanoTime();

        Outputter outputter = new Outputter(out);

        // move programmatically configured json-path over to javascript, for the static javascript to read.
        outputter.html("<script>window.matsbm_json_path = ");
        if (_jsonUrlPath != null) {
            outputter.html("'").DATA(_jsonUrlPath).html("'");
        }
        else {
            outputter.html("null");
        }
        outputter.html(";</script>\n");

        if (requestParameters.containsKey("browse")) {
            // -> Browse Queue
            String queueId = getBrowseQueueId(requestParameters, ac);
            // ----- Passed BROWSE Access Control for specific queueId.

            boolean autoJumpIfSingleMessage = requestParameters.containsKey("autojump");

            BrowseQueue.gui_BrowseQueue(_matsBrokerMonitor, _matsBrokerBrowseAndActions, _monitorAdditions, outputter,
                    queueId, ac, autoJumpIfSingleMessage);
        }
        else if (requestParameters.containsKey("examineMessage")) {
            // -> Examine Message
            String queueId = getBrowseQueueId(requestParameters, ac);
            // ----- Passed BROWSE Access Control for specific queueId.

            // ACCESS CONTROL: examine message
            if (!ac.examineMessage(queueId)) {
                throw new AccessDeniedException("Not allowed to examine message!");
            }

            // ----- Passed EXAMINE MESSAGE Access Control for specific queueId.

            String[] messageSystemIds = requestParameters.get("messageSystemId");
            if (messageSystemIds == null) {
                throw new IllegalArgumentException("Missing messageSystemIds");
            }
            if (messageSystemIds.length > 1) {
                throw new IllegalArgumentException(">1 messageSystemId args");
            }
            String messageSystemId = messageSystemIds[0];

            ExamineMessage.gui_ExamineMessage(_matsBrokerMonitor, _matsBrokerBrowseAndActions, _matsSerializer,
                    _monitorAdditions, outputter, queueId, messageSystemId, ac);
        }
        else {
            // E-> No view argument: Broker Overview

            // ACCESS CONTROL: examine message
            if (!ac.overview()) {
                throw new AccessDeniedException("Not allowed to see broker overview!");
            }
            // ----- Passed Access Control for overview, render it.

            BrokerOverview.gui_BrokerOverview(_matsBrokerMonitor, _monitorAdditions, outputter, requestParameters, ac);
        }

        long nanosTaken_fullRender = System.nanoTime() - nanosAsStart_fullRender;
        outputter.html("Render time: ").DATA(Math.round(nanosTaken_fullRender / 1000d) / 1000d).html(" ms.");
        outputter.html("</div>");
    }

    private String getBrowseQueueId(Map<String, String[]> requestParameters, AccessControl ac) {
        String[] destinationIds = requestParameters.get("destinationId");
        if (destinationIds == null) {
            throw new IllegalArgumentException("Missing destinationId");
        }
        if (destinationIds.length > 1) {
            throw new IllegalArgumentException(">1 browse args");
        }
        String destinationId = destinationIds[0];
        if (!(destinationId.startsWith("queue:") || destinationId.startsWith("topic:"))) {
            throw new IllegalArgumentException("the browse arg should start with queue: or topic:");
        }

        boolean queue = destinationId.startsWith("queue:");
        if (!queue) {
            throw new IllegalArgumentException("Cannot browse anything other than queues!");
        }
        String queueId = destinationId.substring("queue:".length());

        // :: ACCESS CONTROL
        boolean browseAllowed = ac.browseQueue(queueId);
        if (!browseAllowed) {
            throw new AccessDeniedException("Not allowed to browse queue!");
        }
        return queueId;
    }

    // Use Mats3's FieldBasedJacksonMapper creator, since we want the same logics.
    private static final ObjectMapper MAPPER = FieldBasedJacksonMapper.createJacksonObjectMapper();

    @Override
    public void json(Appendable out, Map<String, String[]> requestParameters, String requestBody,
            AccessControl ac) throws IOException, AccessDeniedException {
        log.info("MatsBrokerMonitorHtmlGui: JSON RequestBody: " + requestBody);
        CommandDto command = MAPPER.readValue(requestBody, CommandDto.class);

        if (command.action == null) {
            throw new IllegalArgumentException("'command.action' is null.");
        }

        switch (command.action) {
            // ?: DELETE SELECTED or REISSUE SELECTED
            case "reissue_selected":
            case "mute_selected":
            case "delete_selected": {
                if (command.queueId == null) {
                    throw new IllegalArgumentException("command.queueId is null.");
                }

                if (command.msgSysMsgIds == null) {
                    throw new IllegalArgumentException("command.msgSysMsgIds is null.");
                }

                long nanosAtStart_wait = System.nanoTime();
                log.info(command.action + ": queueId: [" + command.queueId
                        + "], #msgSysMsgIds:[" + command.msgSysMsgIds.size() + "]");

                Map<String, MatsBrokerMessageMetadata> affectedMessages;
                if ("reissue_selected".equals(command.action)) {
                    // ACCESS CONTROL: reissue message
                    if (!ac.reissueMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to reissue messages from DLQ.");
                    }
                    // ----- Passed Access Control for reissueMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.reissueMessages(command.queueId,
                            command.msgSysMsgIds, ac.username());
                }
                else if ("mute_selected".equals(command.action)) {
                    // ACCESS CONTROL: mute message
                    if (!ac.muteMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to mute messages from DLQ.");
                    }

                    String[] muteReasonsA = requestParameters.get("muteReason");
                    String muteReason;
                    if (muteReasonsA == null || muteReasonsA.length == 0) {
                        muteReason = null;
                    }
                    else {
                        muteReason = muteReasonsA[0];
                    }

                    // ----- Passed Access Control for muteMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.muteMessages(command.queueId,
                            command.msgSysMsgIds, ac.username(), muteReason);
                }
                else if ("delete_selected".equals(command.action)) {
                    // ACCESS CONTROL: delete message
                    if (!ac.deleteMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to delete messages from queue.");
                    }
                    // ----- Passed Access Control for deleteMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.deleteMessages(command.queueId,
                            command.msgSysMsgIds);
                }
                else {
                    throw new AssertionError("Should not be able to get here.");
                }

                ResultDto result = new ResultDto();
                result.resultOk = true;
                result.numberOfAffectedMessages = affectedMessages.size();
                result.requestedMsgSysMsgIds = command.msgSysMsgIds;
                result.affectedMessages = affectedMessages;
                result.timeTakenMillis = Math.round((System.nanoTime() - nanosAtStart_wait) / 1000d) / 1000d;
                out.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));

                // Run a forceUpdate to get newer info - this is async, returning "immediately".
                _matsBrokerMonitor.forceUpdate("After_" + command.action + "_"
                        + Long.toString(ThreadLocalRandom.current().nextLong(), 36), false);
                break;
            }
            // ?: REISSUE ALL, MUTE ALL or DELETE ALL
            case "reissue_all":
            case "mute_all":
            case "delete_all": {
                if (command.queueId == null) {
                    throw new IllegalArgumentException("command.queueId is null.");
                }

                if (command.limitMessages <= 0) {
                    throw new IllegalArgumentException("command.limitMessages <= 0.");
                }

                long nanosAtStart_wait = System.nanoTime();
                log.info(command.action + ": queueId: [" + command.queueId
                        + "], limitMessages:[" + command.limitMessages + "]");

                Map<String, MatsBrokerMessageMetadata> affectedMessages;
                if ("reissue_all".equals(command.action)) {
                    // ACCESS CONTROL: reissue message
                    if (!ac.reissueMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to reissue messages from DLQ.");
                    }
                    // ----- Passed Access Control for reissueMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.reissueAllMessages(command.queueId,
                            command.limitMessages, ac.username());
                }
                else if ("mute_all".equals(command.action)) {
                    // ACCESS CONTROL: mute message
                    if (!ac.muteMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to mute messages from DLQ.");
                    }
                    // ----- Passed Access Control for reissueMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.muteAllMessages(command.queueId,
                            command.limitMessages, ac.username(), null);
                }
                else if ("delete_all".equals(command.action)) {
                    // ACCESS CONTROL: delete message
                    if (!ac.deleteMessage(command.queueId)) {
                        throw new AccessDeniedException("Not allowed to delete messages from queue.");
                    }
                    // ----- Passed Access Control for deleteMessage; Perform operation

                    affectedMessages = _matsBrokerBrowseAndActions.deleteAllMessages(command.queueId,
                            command.limitMessages);
                }
                else {
                    throw new AssertionError("Should not be able to get here.");
                }

                ResultDto result = new ResultDto();
                result.resultOk = true;
                result.numberOfAffectedMessages = affectedMessages.size();
                result.requestedMsgSysMsgIds = command.msgSysMsgIds;
                result.affectedMessages = affectedMessages;
                result.timeTakenMillis = Math.round((System.nanoTime() - nanosAtStart_wait) / 1000d) / 1000d;
                out.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));

                // Run a forceUpdate to get newer info - this is async, returning "immediately".
                _matsBrokerMonitor.forceUpdate("After_" + command.action + "_" + random(), false);
                break;
            }
            // ?: FORCE UPDATE?
            case "update": {
                // ACCESS CONTROL: reissue message
                if (!ac.overview()) {
                    throw new AccessDeniedException("Not allowed to see overview, thus not request update either.");
                }
                // ----- Passed Access Control for overview; Request update

                boolean updatedOkWithinTimeout;
                String correlationId = random();
                long nanosAtStart_wait = System.nanoTime();
                try {
                    // :: Logic to make the update synchronous, using correlationId and waiting for the update
                    // event with the same correlationId.
                    CountDownLatch countDownLatch = new CountDownLatch(1);
                    _updateEventWaiters.put(correlationId, countDownLatch);
                    log.info("update: executing matsBrokerMonitor.forceUpdate(\"" + correlationId + "\", false);");
                    _matsBrokerMonitor.forceUpdate(correlationId, true);
                    updatedOkWithinTimeout = countDownLatch.await(FORCE_UPDATE_TIMEOUT, TimeUnit.MILLISECONDS);
                    long nanosTaken_wait = System.nanoTime() - nanosAtStart_wait;
                    log.info("update: updatedOkWithinTimeout: [" + updatedOkWithinTimeout
                            + "] (waited [" + Math.round((nanosTaken_wait / 1000d) / 1000d) + "] ms).");
                }
                catch (InterruptedException e) {
                    log.warn("update: Got interrupted while waiting for matsBrokerMonitor.forceRefresh(" + correlationId
                            + ", false) - assuming shutdown, trying to reply 'no can do'.");
                    updatedOkWithinTimeout = false;
                }
                finally {
                    _updateEventWaiters.remove(correlationId);
                }
                ResultDto result = new ResultDto();
                result.resultOk = updatedOkWithinTimeout;
                result.timeTakenMillis = Math.round((System.nanoTime() - nanosAtStart_wait) / 1000d) / 1000d;
                out.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                break;
            }
            default:
                throw new IllegalArgumentException("Don't understand that 'command.action'.");
        }
    }

    private static class CommandDto {
        String action;
        String queueId;
        List<String> msgSysMsgIds;
        int limitMessages;
    }

    private static class ResultDto {
        boolean resultOk;
        List<String> requestedMsgSysMsgIds;
        Integer numberOfAffectedMessages;
        Map<String, MatsBrokerMessageMetadata> affectedMessages;
        double timeTakenMillis;
    }

    static final DecimalFormat NF_INTEGER;
    static final DecimalFormat NF_3_DECIMALS;
    static {
        DecimalFormatSymbols numFormatSymbols = new DecimalFormatSymbols(Locale.US);
        numFormatSymbols.setDecimalSeparator('.');
        numFormatSymbols.setGroupingSeparator('\u202f');

        NF_INTEGER = new DecimalFormat("#,##0");
        NF_INTEGER.setMaximumFractionDigits(0);
        NF_INTEGER.setDecimalFormatSymbols(numFormatSymbols);

        NF_3_DECIMALS = new DecimalFormat("#,##0.000");
        NF_3_DECIMALS.setMaximumFractionDigits(3);
        NF_3_DECIMALS.setDecimalFormatSymbols(numFormatSymbols);
    }
}
