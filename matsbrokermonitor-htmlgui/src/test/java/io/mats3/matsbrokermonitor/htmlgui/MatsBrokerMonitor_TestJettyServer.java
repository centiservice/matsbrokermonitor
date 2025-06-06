package io.mats3.matsbrokermonitor.htmlgui;

import static io.mats3.matsbrokermonitor.htmlgui.SetupTestMatsEndpoints.SUBSCRIPTION_TERMINATOR;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.activemq.broker.BrokerService;
import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storebrand.healthcheck.HealthCheckLogger;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.HealthCheckRegistry.CreateReportRequest;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl;
import com.storebrand.healthcheck.impl.ServiceInfo;
import com.storebrand.healthcheck.output.HealthCheckTextOutput;

import ch.qos.logback.core.CoreConstants;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyInitiator;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyStageProcessor;
import io.mats3.localinspect.LocalHtmlInspectForMatsFactory;
import io.mats3.localinspect.LocalStatsMatsInterceptor;
import io.mats3.matsbrokermonitor.activemq.ActiveMqMatsBrokerMonitor;
import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination.StageDestinationType;
import io.mats3.matsbrokermonitor.broadcaster.MatsBrokerMonitorBroadcastAndControl;
import io.mats3.matsbrokermonitor.broadcastreceiver.MatsBrokerMonitorBroadcastReceiver;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui.BrokerOverviewAddition;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui.BrowseQueueAddition;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui.ExamineMessageAddition;
import io.mats3.matsbrokermonitor.htmlgui.MatsBrokerMonitorHtmlGui.MonitorAddition;
import io.mats3.matsbrokermonitor.htmlgui.SetupTestMatsEndpoints.DataTO;
import io.mats3.matsbrokermonitor.htmlgui.SetupTestMatsEndpoints.StateTO;
import io.mats3.matsbrokermonitor.htmlgui.impl.MatsBrokerMonitorHtmlGuiImpl;
import io.mats3.matsbrokermonitor.jms.JmsMatsBrokerBrowseAndActions;
import io.mats3.matsbrokermonitor.stbhealthcheck.MatsStorebrandHealthCheck;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.test.broker.MatsTestBroker.ActiveMq;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * @author Endre Stølsvik 2021-12-31 01:50 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsBrokerMonitor_TestJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final Logger log = LoggerFactory.getLogger(MatsBrokerMonitor_TestJettyServer.class);

    public static final String MATS_DESTINATION_PREFIX = "endre:";

    private static String SERVICE = "MatsTestBrokerMonitor";
    private static String SERVICE_1 = SERVICE + ".FirstSubService";
    private static String SERVICE_2 = SERVICE + ".SecondSubService";
    private static String SERVICE_3 = "Another Group With Spaces.SubService";

    private static final String APP_NAME = "MatsBrokerMonitor";
    private static final String APP_VERSION = "#testing#";

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private MatsFactory _matsFactory;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            log.info("ServletContextListener.contextInitialized(...): " + sce);
            ServletContext sc = sce.getServletContext();
            log.info("  \\- ServletContext: " + sc);

            // ## Create MatsFactory
            // Get JMS ConnectionFactory from ServletContext
            ConnectionFactory connFactory = (ConnectionFactory) sc.getAttribute(ConnectionFactory.class.getName());
            // MatsSerializer
            MatsSerializer matsSerializer = MatsSerializerJson.create();
            // Create the MatsFactory
            _matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(APP_NAME, APP_VERSION,
                    JmsMatsJmsSessionHandler_Pooling.create(connFactory, PoolingKeyInitiator.FACTORY,
                            PoolingKeyStageProcessor.FACTORY),
                    matsSerializer);
            // Hold endpoints
            _matsFactory.holdEndpointsUntilFactoryIsStarted();
            // Configure the MatsFactory for testing (remember, we're running two instances in same JVM)
            FactoryConfig factoryConfig = _matsFactory.getFactoryConfig();
            // .. Concurrency of only 2
            factoryConfig.setConcurrency(SetupTestMatsEndpoints.BASE_CONCURRENCY);
            // .. Mats Managed DLQ Divert, only a few deliveries
            ((JmsMatsFactory) _matsFactory).setMatsManagedDlqDivert(3);
            // .. Use port number of current server as postfix for name of MatsFactory, and of nodename
            Integer portNumber = (Integer) sc.getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);
            factoryConfig.setName(getClass().getSimpleName() + "_" + portNumber);
            factoryConfig.setNodename(factoryConfig.getNodename() + "_" + portNumber);
            factoryConfig.setMatsDestinationPrefix(MATS_DESTINATION_PREFIX);
            // Put it in ServletContext, for servlet to get
            sc.setAttribute(JmsMatsFactory.class.getName(), _matsFactory);

            // Install the local stats keeper interceptor
            LocalStatsMatsInterceptor.install(_matsFactory);

            // Make Futurizer:
            MatsFuturizer matsFuturizer = MatsFuturizer.createMatsFuturizer(_matsFactory, SERVICE);
            sc.setAttribute(MatsFuturizer.class.getName(), matsFuturizer);

            // Setup test endpoints
            SetupTestMatsEndpoints.setupMatsTestEndpoints(SERVICE_1, _matsFactory);
            SetupTestMatsEndpoints.setupMatsTestEndpoints(SERVICE_2, _matsFactory);
            SetupTestMatsEndpoints.setupMatsTestEndpoints(SERVICE_3, _matsFactory);

            MatsBrokerMonitorBroadcastReceiver receiver = MatsBrokerMonitorBroadcastReceiver.install(_matsFactory);
            receiver.registerListener(updateEvent -> {
                log.info("Received update via Mats fabric: " + updateEvent);
                log.info(".. BrokerInfo: " + updateEvent.getBrokerInfo());
                for (Map.Entry<String, MatsBrokerDestination> entry : updateEvent.getEventDestinations().entrySet()) {
                    log.info("  .. Destination [" + entry.getKey() + "]: " + entry.getValue());
                }
            });
            sc.setAttribute(MatsBrokerMonitorBroadcastReceiver.class.getName(), receiver);

            _matsFactory.start();

            // :: Create the "local inspect"
            LocalHtmlInspectForMatsFactory inspect = LocalHtmlInspectForMatsFactory.create(_matsFactory);
            sc.setAttribute(LocalHtmlInspectForMatsFactory.class.getName(), inspect);

            // :: Create the ActiveMqMatsBrokerMonitor #1
            MatsBrokerMonitor matsBrokerMonitor1 = ActiveMqMatsBrokerMonitor
                    .createWithDestinationPrefix(connFactory, MATS_DESTINATION_PREFIX, 15_000);
            // Register a dummy listener
            matsBrokerMonitor1.registerListener(updateEvent -> {
                log.info("Listener for MBM #1 at TestJettyServer: Got update! " + updateEvent);
                updateEvent.getEventDestinations().forEach((fqName, matsBrokerDestination) -> log
                        .info(".. event destinations: [" + fqName + "] = [" + matsBrokerDestination + "]"));
            });
            MatsBrokerMonitorBroadcastAndControl.install(matsBrokerMonitor1, _matsFactory);
            matsBrokerMonitor1.start();

            // :: Create the ActiveMqMatsBrokerMonitor #2
            MatsBrokerMonitor matsBrokerMonitor2 = ActiveMqMatsBrokerMonitor
                    .createWithDestinationPrefix(connFactory, MATS_DESTINATION_PREFIX, 15_000);
            // Register a dummy listener
            matsBrokerMonitor2.registerListener(destinationUpdateEvent -> {
                log.info("Listener for MBM #2 at TestJettyServer: Got update! " + destinationUpdateEvent);
            });
            MatsBrokerMonitorBroadcastAndControl.install(matsBrokerMonitor2, _matsFactory);
            matsBrokerMonitor2.start();

            // Put it in ServletContext, for shutdown
            sc.setAttribute("matsBrokerMonitor1", matsBrokerMonitor1);
            sc.setAttribute("matsBrokerMonitor2", matsBrokerMonitor2);

            // :: Create the MonitorAdditions
            List<? super MonitorAddition> monitorAdditions = new ArrayList<>();

            // BrokerOverviewAdditions
            monitorAdditions.add(new BrokerOverviewAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx) {
                    return "<span class='spacer'></span>Testing dummy added overview-contents for [" + ctx.getAccessControl().username() + "]: "
                            + "<input type='button' value='Type: Button' class='matsbm_button matsbm_button_blue'>"
                            + "</input>";
                }
            });
            monitorAdditions.add(new BrokerOverviewAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx) {
                    return "<a href='http://www.google.com' class='matsbm_button matsbm_button_yellow'>Link Button"
                            + " <span class='matsbm_external_link_icon'></span></a>";
                }
            });

            // BrowseQueueAdditions
            monitorAdditions.add(new BrowseQueueAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx, QueueRep queue) {
                    String destinationName = queue.getMatsBrokerDestination().getDestinationName();
                    // Chop from last dot to end, if present - otherwise full string
                    int lastDot = destinationName.lastIndexOf('.');
                    String destinationNameChopped = lastDot == -1 ? destinationName
                            : destinationName.substring(lastDot + 1);
                    return "<span class='spacer'></span><span>Testing dummy added content for ["
                            + ctx.getAccessControl().username() + "]</span>: ";
                }

                @Override
                public String getColumnHeadingHtmlFor(AdditionContext ctx, QueueRep queue) {
                    String destinationName = queue.getMatsBrokerDestination().getDestinationName();
                    // Chop from last dot to end, if present - otherwise full string
                    int lastDot = destinationName.lastIndexOf('.');
                    String destinationNameChopped = lastDot == -1 ? destinationName
                            : destinationName.substring(lastDot + 1);
                    return "<th>Added table heading: " + destinationNameChopped + "</th>";
                }

                @Override
                public String getCellHtmlFor(AdditionContext ctx, MessageRep message) {
                    String matsMessageId = message.getMatsBrokerMessageRepresentation().getMatsMessageId();
                    return "<td><div>Added table cell [" + (matsMessageId != null ? matsMessageId.hashCode() : "null")
                            + "]</div></td>";
                }
            });
            monitorAdditions.add(new BrowseQueueAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx, QueueRep queue) {
                    return "<input type='button' value='Type: Button' class='matsbm_button matsbm_button_blue'></input>"
                            + "<a href='http://www.google.com' class='matsbm_button matsbm_button_yellow'>Link Button"
                            + " <span class='matsbm_external_link_icon' /></a>";
                }

                @Override
                public String getColumnHeadingHtmlFor(AdditionContext ctx, QueueRep queue) {
                    return "<th style='background:blue'>Added: Head</th>";
                }

                @Override
                public String getCellHtmlFor(AdditionContext ctx, MessageRep message) {
                    return "<td style='background: green'><div>Added: Cell [" + ctx.getAccessControl().username()
                            + "]</div></td>";
                }
            });

            // ExamineMessageAdditions
            monitorAdditions.add(new ExamineMessageAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx, MessageRep message) {
                    return "<span class='spacer'></span><h2>Added h2-text very long indeed annoyingly wrappable even"
                            + " longer test for user [" + ctx.getAccessControl().username() + "]!!</h2>";
                }
            });
            monitorAdditions.add(new ExamineMessageAddition() {
                @Override
                public String getButtonRowHtmlFor(AdditionContext ctx, MessageRep message) {
                    return "<input type='button' value='Added button!' class='matsbm_button'></input>";
                }
            });

            // :: Create the JmsMatsBrokerBrowseAndActions #1 (don't need two of this)
            MatsBrokerBrowseAndActions matsBrokerBrowseAndActions1 = JmsMatsBrokerBrowseAndActions
                    .createWithDestinationPrefix(connFactory, MATS_DESTINATION_PREFIX);

            // :: Create the MatsBrokerMonitorHtmlGui #1 (don't need two of this)
            MatsBrokerMonitorHtmlGuiImpl matsBrokerMonitorHtmlGui1 = MatsBrokerMonitorHtmlGui
                    .create(matsBrokerMonitor1, matsBrokerBrowseAndActions1, monitorAdditions, matsSerializer);

            // For multiple ActiveMQs:
            // Either: an identifier of sorts, so that the MatsBrokerMonitor knows if it is talked to.
            // Or: .. just use "URL routing" to target the different, i.e. put them on different URL paths.
            // Worth remembering: This is different from a MatsFactory, in that it monitors the _underlying_ broker, not
            // the "local" MatsFactory.

            // Put it in ServletContext, for servlet to get
            sc.setAttribute("matsBrokerMonitorHtmlGui1", matsBrokerMonitorHtmlGui1);

            // ::: Create Storebrand HealthCheckRegistry and Mats3 HealthCheck
            HealthCheckLogger healthCheckLogger = dto -> log.info("HealthCheck: " + dto);
            HealthCheckRegistryImpl healthCheckRegistry = new HealthCheckRegistryImpl(Clock.systemDefaultZone(),
                    healthCheckLogger, new ServiceInfo(APP_NAME, APP_VERSION));
            MatsStorebrandHealthCheck.registerMatsHealthCheck(healthCheckRegistry, _matsFactory);
            MatsStorebrandHealthCheck.registerMatsHealthCheck(healthCheckRegistry, _matsFactory);
            // Start the HealthCheck subsystem
            healthCheckRegistry.startHealthChecks();

            // .. put the HealtCheckRegistry in the ServletContext, for the HealthCheckServlet to get.
            sc.setAttribute(HealthCheckRegistry.class.getName(), healthCheckRegistry);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("ServletContextListener.contextDestroyed(..): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());
            _matsFactory.stop(5000);
            ((MatsBrokerMonitor) sce.getServletContext().getAttribute("matsBrokerMonitor1")).close();
            ((MatsBrokerMonitor) sce.getServletContext().getAttribute("matsBrokerMonitor2")).close();
        }
    }

    /**
     * Menu.
     */
    @WebServlet("/")
    public static class RootServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/html; charset=utf-8");
            PrintWriter out = res.getWriter();
            out.println("<title>MBM Menu</title>");
            out.println("<h1>MatsBrokerMonitor TestJettyServer Menu</h1>");
            out.println("<a href=\"./matsbrokermonitor\">MatsBrokerMonitor HTML GUI</a><br>");
            out.println("<a href=\"./healthcheck\">HealthCheck - using Storebrand HealthCheck</a><br>");
            out.println("<a href=\"./sendRequest?count=1\">Send Mats requests, count=1</a>");
            out.println(" - <i>or</i> - <a href=\"./sendRequest?count=500\">count=500</a>");
            out.println(" - <i>or</i> - <a href=\"./sendRequest?count=2000\">count=2000</a><br>");
            out.println("<a href=\"./forceUpdate\">Force Update (full) 'remotely' via <code>"
                    + MatsBrokerMonitorBroadcastReceiver.class.getSimpleName() + "</code></a><br>");
            out.println("<br/><a href=\"./shutdown\">Shutdown - to test clean shutdown procedure</a><br>");
        }
    }

    /**
     * HealthCheckRegistry
     */
    @WebServlet("/healthcheck")
    public static class HealthCheck extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/plain; charset=utf-8");
            boolean sync = req.getParameter("sync") != null;
            PrintWriter out = res.getWriter();
            HealthCheckRegistry healthCheckRegistry = (HealthCheckRegistry) req.getServletContext()
                    .getAttribute(HealthCheckRegistry.class.getName());
            HealthCheckReportDto report = healthCheckRegistry
                    .createReport(new CreateReportRequest().forceFreshData(sync));
            HealthCheckTextOutput.printHealthCheckReport(report, out);
        }
    }

    /**
     * Force update via MatsBrokerMonitorBroadcastReceiver
     */
    @WebServlet("/forceUpdate")
    public static class ForceUpdateServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            String correlationId = "CorrelationId:" + Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()),
                    36);
            res.getWriter().println("Sending ForceUpdate via '" + MatsBrokerMonitorBroadcastReceiver.class
                    .getSimpleName() + "', using CorrelationId [" + correlationId + "]");

            MatsBrokerMonitorBroadcastReceiver mbmbr = (MatsBrokerMonitorBroadcastReceiver) req.getServletContext()
                    .getAttribute(MatsBrokerMonitorBroadcastReceiver.class.getName());

            mbmbr.forceUpdate(correlationId, true);
        }
    }

    /**
     * Send Mats request - notice the "count" URL parameter.
     */
    @WebServlet("/sendRequest")
    public static class SendRequestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/plain; charset=utf-8");
            ServletContext servletContext = req.getServletContext();
            MatsFuturizer matsFuturizer = (MatsFuturizer) servletContext.getAttribute(MatsFuturizer.class.getName());
            MatsFactory matsFactory = (MatsFactory) servletContext.getAttribute(JmsMatsFactory.class.getName());

            PrintWriter out = res.getWriter();

            sendAFuturize(out, matsFuturizer, false);
            sendAFuturize(out, matsFuturizer, true);

            takeNap(500);

            out.println("===========\n");

            sendAFuturize(out, matsFuturizer, false);
            sendAFuturize(out, matsFuturizer, true);

            int count = 1;
            String countS = req.getParameter("count");
            if (countS != null) {
                count = Integer.parseInt(countS);
            }
            int countF = count;

            out.println("Sending [" + count + "] requests ..");

            long nanosStart_sendMessages = System.nanoTime();
            StateTO sto = new StateTO(420, 420.024);
            DataTO dto = new DataTO(42, "TheAnswer");
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        for (int i = 0; i < countF; i++) {

                            KeepTrace keepTrace = KeepTrace.FULL;
                            if (i % 3 == 0) {
                                keepTrace = KeepTrace.COMPACT;
                            }
                            else if (i % 3 == 1) {
                                keepTrace = KeepTrace.MINIMAL;
                            }

                            msg.traceId(MatsTestHelp.traceId() + "_#&'\"<" + i + ">")
                                    // ORDINARY messages
                                    .keepTrace(keepTrace)
                                    .from("/sendRequestInitiated")
                                    .to(SERVICE_1 + SetupTestMatsEndpoints.SERVICE_MAIN)
                                    .replyTo(SERVICE_1 + SetupTestMatsEndpoints.TERMINATOR, sto)
                                    .request(dto, new StateTO(1, 2));
                        }
                    });
            double msTaken_sendMessages = (System.nanoTime() - nanosStart_sendMessages) / 1_000_000d;
            out.println(".. [" + count + "] requests sent, took [" + msTaken_sendMessages + "] ms.");
            out.println();

            out.println("Sending a message that will throw on 'midMethod' Stage2!\n");
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        msg.traceId(MatsTestHelp.traceId() + "_shallThrowOnMidMethodStage2")
                                .keepTrace(KeepTrace.FULL)
                                .from("/sendRequestInitiated")
                                .to(SERVICE_1 + SetupTestMatsEndpoints.SERVICE_MAIN)
                                .replyTo(SERVICE_1 + SetupTestMatsEndpoints.TERMINATOR, sto)
                                .setTraceProperty(SetupTestMatsEndpoints.THROW, true)
                                .request(dto, new StateTO(1, 2));
                    });

            // :: Send a message to a non-existent endpoint, to have an endpoint with a non-consumed message.
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        msg.traceId(MatsTestHelp.traceId() + "_nonExistentEndpoint")
                                .keepTrace(KeepTrace.FULL)
                                .nonPersistent(60_000)
                                .noAudit()
                                .interactive()
                                .from("/sendRequestInitiated")
                                .to(SERVICE + ".NonExistentService.nonExistentMethod")
                                .send(dto, new StateTO(1, 2));
                    });

            // :: Send a message to non-existend endpoint, but with a long state text.
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        StateTO initialTargetSto = new StateTO(1, 2);
                        initialTargetSto.text = "43289432890532870jklr2jf980gfj234980gj4290gj49g4j290g4j20g423"
                                + "gj904gj42930gj3490gj4390gj4309gj4239058i34290fgj49032ut609342jtg09234ut9034256tu34"
                                + "gj34290g432j90g43j09g43j90g432j09g34u5609t43u59043utg9043jg9043j90g g4390gj4390gj943"
                                + "0gj3490gj4390gj4390gj439g34j90g43kjg940jkg9043j90g43u9543 jit9043i943ui904353490"
                                + "jg9034jg4309jg4390jg0934jg4039gj4309gj4390";
                        msg.traceId(MatsTestHelp.traceId() + "_nonExistentEndpoint")
                                .keepTrace(KeepTrace.FULL)
                                .from("/sendRequestInitiated")
                                .to(SERVICE + ".NonExistentService.nonExistentMethod")
                                .send(dto, initialTargetSto);
                    });

            // :: Send a message to a SubscriptionTerminator that will throw.
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        StateTO initialTargetSto = new StateTO(1, 2);
                        initialTargetSto.text = "Just some text";
                        msg.traceId(MatsTestHelp.traceId() + "_SubscriptionThatShouldThrow")
                                .keepTrace(KeepTrace.FULL)
                                .from("/sendRequestInitiated")
                                .setTraceProperty(SetupTestMatsEndpoints.THROW, true)
                                .to(SERVICE_1 + SUBSCRIPTION_TERMINATOR)
                                .publish(dto, initialTargetSto);
                    });

            // :: Send a message to a Leaf with replyToSubscription, which will throw.
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        StateTO initialTargetSto = new StateTO(1, 2);
                        initialTargetSto.text = "Just some text";
                        msg.traceId(MatsTestHelp.traceId() + "_ReplyToSubscriptionThatShouldThrow")
                                .keepTrace(KeepTrace.FULL)
                                .from("/sendRequestInitiated")
                                .setTraceProperty(SetupTestMatsEndpoints.THROW, true)
                                .to(SERVICE_1 + SetupTestMatsEndpoints.SERVICE_LEAF)
                                .replyToSubscription(SERVICE_1 + SUBSCRIPTION_TERMINATOR, new StateTO(1, 2))
                                .request(dto, initialTargetSto);
                    });

            // :: A bunch of messages to SuperSlow
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        for (int i = 0; i < Math.max(1, countF / 2); i++) {
                            msg.traceId(MatsTestHelp.traceId() + i)
                                    .from("/sendRequestInitiated")
                                    .to(SERVICE_1 + SetupTestMatsEndpoints.SERVICE_SUPERSLOW)
                                    .replyTo(SERVICE_1 + SetupTestMatsEndpoints.TERMINATOR, sto)
                                    .request(dto, new StateTO(1, 2));
                        }

                        for (int i = 0; i < countF; i++) {
                            msg.traceId(MatsTestHelp.traceId() + i)
                                    .from("/sendRequestInitiated")
                                    .to(SERVICE_2 + SetupTestMatsEndpoints.SERVICE_SUPERSLOW)
                                    .replyTo(SERVICE_2 + SetupTestMatsEndpoints.TERMINATOR, sto)
                                    .request(dto, new StateTO(1, 2));
                        }
                    });

            // :: Send a message to the ActiveMQ and Artemis Global DLQs
            // Get JMS ConnectionFactory from ServletContext
            ConnectionFactory connFactory = (ConnectionFactory) req.getServletContext()
                    .getAttribute(ConnectionFactory.class.getName());
            try {
                Connection connection = connFactory.createConnection();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageProducer producer = session.createProducer(null); // Generic producer

                Consumer<String> sendToQueue = dlqName -> {
                    out.println("Sending to [" + dlqName + "]." + "\n");
                    try {
                        Queue dlq = session.createQueue(dlqName);
                        Message message = session.createMessage();
                        producer.send(dlq, message);
                    }
                    catch (JMSException e) {
                        throw new RuntimeException("Couldn't send a message to DLQ.");
                    }
                };

                // ::: SEND TEST MESSAGES TO CHECK HANDLING

                // :: The ActiveMQ Global DLQ (handled specially)
                sendToQueue.accept("ActiveMQ.DLQ");

                // :: A few non-Mats3 queues, to check handling of such
                sendToQueue.accept("DLQ");
                sendToQueue.accept("TestQueue.RandomQueue");
                sendToQueue.accept("DLQ.TestQueue.RandomQueue");

                // :: Send to a Fake endpoint (so that they are not consumed), on Standard and DLQ
                sendToQueue.accept(MATS_DESTINATION_PREFIX
                        + StageDestinationType.STANDARD.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithoutConsumers");
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithoutConsumers");

                // :: Send to another Fake endpoint (so that they are not consumed), on DLQ only.
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE.getMidfix()
                        + "FakeMatsEndpoint.methodWithJustDLQ");

                // :: Send to another Fake endpoint (so that they are not consumed), on Muted only.
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE_MUTED.getMidfix()
                        + "FakeMatsEndpoint.methodWithJustMutedDLQ");

                // :: ALL The different types of StageDestinationTypes for a fake Endpoint (i.e. no consumers there)
                // .. Standard
                sendToQueue.accept(MATS_DESTINATION_PREFIX
                        + StageDestinationType.STANDARD.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues");
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues");
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE_MUTED.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues");

                // .. Non-persistent Interactive
                sendToQueue.accept(MATS_DESTINATION_PREFIX
                        + StageDestinationType.NON_PERSISTENT_INTERACTIVE.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues");
                sendToQueue.accept("DLQ." + MATS_DESTINATION_PREFIX
                        + StageDestinationType.DEAD_LETTER_QUEUE_NON_PERSISTENT_INTERACTIVE.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues");
                // !NOTE! Using same MUTED as normal.

                // .. Wiretap
                sendToQueue.accept(MATS_DESTINATION_PREFIX
                        + StageDestinationType.WIRETAP.getMidfix() + "FakeMatsEndpoint.someMethodWithAllQueues");

                // .. Add one more stage, to check visual appearance when shrunk window
                sendToQueue.accept(MATS_DESTINATION_PREFIX
                        + StageDestinationType.STANDARD.getMidfix()
                        + "FakeMatsEndpoint.someMethodWithAllQueues.stage1");

                connection.close();
            }
            catch (JMSException e) {
                throw new IOException("Couldn't send a message to DLQ.");
            }

            // :: Chill till this has really gotten going.
            out.println("Chilling 200 ms to let the [" + countF + "] request messages really get into the system.\n");
            takeNap(200);

            Future<Reply<DataTO>> futureNonInteractive = sendAFuturize(out, matsFuturizer, false);
            Future<Reply<DataTO>> futureInteractive = sendAFuturize(out, matsFuturizer, true);

            out.flush();

            try {
                futureInteractive.get();
                futureNonInteractive.get();
            }
            catch (Exception e) {
                throw new IOException("Future threw", e);
            }

            takeNap(100);
            out.println("done");
        }

        private static void takeNap(int numMillis) throws IOException {
            try {
                Thread.sleep(numMillis);
            }
            catch (InterruptedException e) {
                throw new IOException("Huh?", e);
            }

        }

        private Future<Reply<DataTO>> sendAFuturize(PrintWriter out, MatsFuturizer matsFuturizer, boolean interactive) {
            // :: Do an interactive run, which should "jump the line" throughout the system:
            out.println("Doing a MatsFuturizer." + (interactive ? "interactive" : "NON-interactive") + ".");
            long nanosStart_futurize = System.nanoTime();
            CompletableFuture<Reply<DataTO>> futurized = matsFuturizer.futurize("TraceId_" + Math.random(),
                    "/sendRequest_futurized", SERVICE_1 + SetupTestMatsEndpoints.SERVICE_MAIN, 2, TimeUnit.MINUTES,
                    DataTO.class, new DataTO(5, "fem"), msg -> {
                        msg.setTraceProperty(SetupTestMatsEndpoints.DONT_THROW, Boolean.TRUE);
                        msg.keepTrace(KeepTrace.MINIMAL);
                        if (interactive) {
                            // The setting of non-persistent used to work really strange with ActiveMQ:
                            // The reason is that they have "two buckets" to pick messages from, and if it is currently
                            // draining from the persistent bucket, it will not pick up the non-persistent messages
                            // until the persistent bucket is empty. The result is that the non-persistent messages
                            // are starved - even if they have way higher priority. I've made a hack for ActiveMQ
                            // to forcibly alternate between the buckets if there are messages in both.
                            msg.nonPersistent();
                            msg.interactive();
                        }
                    });
            double msTaken_futurize = (System.nanoTime() - nanosStart_futurize) / 1_000_000d;
            out.println(".. SENT matsFuturizer." + (interactive ? "interactive" : "NON-interactive")
                    + " took [" + msTaken_futurize + "] ms.");
            out.println();

            futurized.thenAccept(dataTOReply -> {
                double msTaken_futurizedReturn = (System.nanoTime() - nanosStart_futurize) / 1_000_000d;
                out.println(".. #DONE# Received futurized " + (interactive ? "interactive" : "NON-interactive")
                        + " took [" + msTaken_futurizedReturn + "] ms.");
                out.println();
                out.flush();
            });
            return futurized;
        }
    }

    /**
     * MatsBrokerMonitorServlet
     */
    @WebServlet("/matsbrokermonitor/*")
    public static class MatsBrokerMonitorServlet extends HttpServlet {

        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
            doJson(req, res);
        }

        @Override
        protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
            doJson(req, res);
        }

        protected void doJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
            // :: MatsBrokerMonitorHtmlGUI instance
            MatsBrokerMonitorHtmlGui brokerMonitorHtmlGui = (MatsBrokerMonitorHtmlGui) req.getServletContext()
                    .getAttribute("matsBrokerMonitorHtmlGui1");

            String body = req.getReader().lines().collect(Collectors.joining("\n"));
            res.setContentType("application/json; charset=utf-8");
            PrintWriter out = res.getWriter();
            brokerMonitorHtmlGui.json(out, req.getParameterMap(), body, MatsBrokerMonitorHtmlGui
                    .getAccessControlAllowAll(System.getProperty("user.name", "-unknown-")));
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/html; charset=utf-8");

            PrintWriter out = res.getWriter();

            // NOTE! This stuff here is just to verify that MatsBrokerMonitor is totally self-sufficient wrt. its
            // JavaScript and CSS, i.e. that it works with, and without, a toolkit like Bootstrap.

            boolean includeBootstrap3 = req.getParameter("includeBootstrap3") != null;
            boolean includeBootstrap4 = req.getParameter("includeBootstrap4") != null;
            boolean includeBootstrap5 = req.getParameter("includeBootstrap5") != null;

            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("  <head>");
            out.println("    <title>MBM HTML GUI</title>");
            if (includeBootstrap3) {
                out.println("    <script src=\"https://code.jquery.com/jquery-1.10.1.min.js\"></script>\n");
                out.println("    <link rel=\"stylesheet\" href=\"https://netdna.bootstrapcdn.com/"
                        + "bootstrap/3.0.2/css/bootstrap.min.css\" />\n");
                out.println("    <script src=\"https://netdna.bootstrapcdn.com/"
                        + "bootstrap/3.0.2/js/bootstrap.min.js\"></script>\n");
            }
            if (includeBootstrap4) {
                out.println("    <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@4.6.0/dist/css/bootstrap.min.css\""
                        + " integrity=\"sha384-B0vP5xmATw1+K9KRQjQERJvTumQW0nPEzvF6L/Z6nronJ3oUOFUFpCjEUQouq2+l\""
                        + " crossorigin=\"anonymous\">\n");
                out.println("<script src=\"https://code.jquery.com/jquery-3.5.1.slim.min.js\""
                        + " integrity=\"sha384-DfXdz2htPH0lsSSs5nCTpuj/zy4C+OGpamoFVy38MVBnE+IbbVYUew+OrCXaRkfj\""
                        + " crossorigin=\"anonymous\"></script>\n");
                out.println("<script src=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@4.6.0/dist/js/bootstrap.bundle.min.js\""
                        + " integrity=\"sha384-Piv4xVNRyMGpqkS2by6br4gNJ7DXjqk09RmUpJ8jgGtD7zP9yug3goQfGII0yAns\""
                        + " crossorigin=\"anonymous\"></script>\n");
            }
            if (includeBootstrap5) {
                out.println("<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@5.0.0-beta3/dist/css/bootstrap.min.css\""
                        + " integrity=\"sha384-eOJMYsd53ii+scO/bJGFsiCZc+5NDVN2yr8+0RDqr0Ql0h+rP48ckxlpbzKgwra6\""
                        + " crossorigin=\"anonymous\">\n");
                out.println("<script src=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@5.0.0-beta3/dist/js/bootstrap.bundle.min.js\""
                        + " integrity=\"sha384-JEW9xMcG8R+pH31jmWH6WWP0WintQrMb4s7ZOdauHnUtxwoG2vI5DkLtS3qm9Ekf\""
                        + " crossorigin=\"anonymous\"></script>\n");
            }
            out.println("  </head>");

            outputMatsBrokerMonitorAndLocalInspect(req, out, includeBootstrap3);

            out.println("</html>");
        }

        private static void outputMatsBrokerMonitorAndLocalInspect(HttpServletRequest req, PrintWriter out,
                boolean includeBootstrap3) throws IOException {
            // :: MatsBrokerMonitorHtmlGUI instance
            MatsBrokerMonitorHtmlGui brokerMonitorHtmlGui = (MatsBrokerMonitorHtmlGui) req.getServletContext()
                    .getAttribute("matsBrokerMonitorHtmlGui1");

            // :: LocalHtmlInspectForMatsFactory instance
            LocalHtmlInspectForMatsFactory localInspect = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute(LocalHtmlInspectForMatsFactory.class.getName());

            // NOTE: Setting "margin: 0" just to be able to compare against the Bootstrap-versions without too
            // much "accidental difference" due to the Bootstrap's setting of margin=0.
            out.println("  <body style=\"margin: 0;\">");
            out.println("    <style>");
            brokerMonitorHtmlGui.outputStyleSheet(out); // Include just once, use the first.
            localInspect.getStyleSheet(out); // Include just once, use the first.
            out.println("    </style>");
            out.println("    <script>");
            brokerMonitorHtmlGui.outputJavaScript(out); // Include just once, use the first.
            localInspect.getJavaScript(out);
            out.println("    </script>");
            out.println(" <a href=\"sendRequest?count=500\">Send request, count=500</a> - for getting some traffic"
                    + " with messages and DLQs to browse!<br>");
            out.println(" <a href=\".\">Go to menu</a><br><br>");

            // :: Bootstrap3 sets the body's font size to 14px.
            // We scale all the affected rem-using elements back up to check consistency.
            if (includeBootstrap3) {
                out.write("<div style=\"font-size: 114.29%\">\n");
            }
            out.println("    <h1>MatsBrokerMonitor HTML embedded GUI</h1>");
            Map<String, String[]> parameterMap = req.getParameterMap();
            brokerMonitorHtmlGui.html(out, parameterMap, MatsBrokerMonitorHtmlGui
                    .getAccessControlAllowAll(System.getProperty("user.name", "-unknown-")));
            if (includeBootstrap3) {
                out.write("</div>\n");
            }

            // Localinspect
            out.write("    <h1>LocalHtmlInspectForMatsFactory</h1>\n");
            localInspect.createFactoryReport(out, true, true, true);
            out.println("  </body>");
        }
    }

    public static Server createServer(ConnectionFactory jmsConnectionFactory, int port) {
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setBaseResource(Resource.newClassPathResource("webapp"));
        // If any problems starting context, then let exception through so that we can exit.
        webAppContext.setThrowUnavailableOnStartupException(true);
        // Store the port number this server shall run under in the ServletContext.
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);
        // Store the JMS ConnectionFactory in the ServletContext
        webAppContext.getServletContext().setAttribute(ConnectionFactory.class.getName(), jmsConnectionFactory);

        // Override the default configurations, stripping down and adding AnnotationConfiguration.
        // https://www.eclipse.org/jetty/documentation/9.4.x/configuring-webapps.html
        // Note: The default resides in WebAppContext.DEFAULT_CONFIGURATION_CLASSES
        webAppContext.setConfigurations(new Configuration[] {
                // new WebInfConfiguration(),
                new WebXmlConfiguration(), // Evidently adds the DefaultServlet, as otherwise no read of "/webapp/"
                // new MetaInfConfiguration(),
                // new FragmentConfiguration(),
                new AnnotationConfiguration() // Adds Servlet annotation processing.
        });

        // :: Get Jetty to Scan project classes too: https://stackoverflow.com/a/26220672/39334
        // Find location for current classes
        URL classesLocation = MatsBrokerMonitor_TestJettyServer.class.getProtectionDomain().getCodeSource()
                .getLocation();
        // Set this location to be scanned.
        webAppContext.getMetaData().setWebInfClassesDirs(Collections.singletonList(Resource.newResource(
                classesLocation)));

        webAppContext.setThrowUnavailableOnStartupException(true);

        // Create the actual Jetty Server
        Server server = new Server(port);

        // Add StatisticsHandler (to enable graceful shutdown), put in the WebApp Context
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(webAppContext);
        server.setHandler(stats);

        // Add a Jetty Lifecycle Listener
        server.addLifeCycleListener(new Listener() {
            @Override
            public void lifeCycleFailure(LifeCycle event, Throwable cause) {
                log.error("====# FAILURE! ===========================================", cause);
            }

            @Override
            public void lifeCycleStarting(LifeCycle event) {
                log.info("====# STARTING! ===========================================");
            }

            @Override
            public void lifeCycleStarted(LifeCycle event) {
                log.info("====# STARTED! ===========================================");
            }

            @Override
            public void lifeCycleStopping(LifeCycle event) {
                log.info("====# STOPPING! ===========================================");
            }

            @Override
            public void lifeCycleStopped(LifeCycle event) {
                log.info("====# STOPPED! ===========================================");
            }
        });

        // :: Graceful shutdown
        server.setStopTimeout(1000);
        server.setStopAtShutdown(true);
        return server;
    }

    public static void main(String... args) throws Exception {
        // Turn off LogBack's absurd SCI
        System.setProperty(CoreConstants.DISABLE_SERVLET_CONTAINER_INITIALIZER_KEY, "true");

        // :: Get ConnectionFactory
        ConnectionFactory jmsConnectionFactory;

        String mode = "ActiveMQ_SpinUpLocalhost";

        if ("MatsTestBroker_Artemis".equals(mode)) {
            System.setProperty("mats.test.broker", "artemis");
            MatsTestBroker matsTestBroker = MatsTestBroker.create();
            jmsConnectionFactory = matsTestBroker.getConnectionFactory();
        }
        else if ("MatsTestBroker_ActiveMQ".equals(mode)) {
            System.setProperty("mats.test.broker", "activemq");
            MatsTestBroker matsTestBroker = MatsTestBroker.create();
            jmsConnectionFactory = matsTestBroker.getConnectionFactory();
        }
        else if ("ActiveMQ_LocalhostConnectionFactory".equals(mode)) {
            jmsConnectionFactory = MatsTestBroker.newActiveMqConnectionFactory("tcp://localhost:61616");
        }
        else if ("ActiveMQ_SpinUpLocalhost".equals(mode)) {
            BrokerService broker = MatsTestBroker.newActiveMqBroker(ActiveMq.LOCALHOST, ActiveMq.SHUTDOWNHOOK);
            jmsConnectionFactory = MatsTestBroker.newActiveMqConnectionFactory("tcp://localhost:61616");
        }
        else {
            throw new IllegalStateException("No mode");
        }

        Server server = createServer(jmsConnectionFactory, 8080);
        try {
            server.start();
        }
        catch (Throwable t) {
            log.error("server.start() got thrown out!", t);
            System.exit(1);
        }
        log.info("MAIN EXITING!!");
    }
}
