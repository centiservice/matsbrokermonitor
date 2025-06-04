package io.mats3.matsbrokermonitor.htmlgui;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions;
import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions.MatsBrokerMessageRepresentation;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor;
import io.mats3.matsbrokermonitor.api.MatsBrokerMonitor.MatsBrokerDestination;
import io.mats3.matsbrokermonitor.htmlgui.impl.MatsBrokerMonitorHtmlGuiImpl;
import io.mats3.serial.MatsSerializer;

/**
 * A HTML UI for the MatsBrokerMonitor system. Meant to be created as a singleton using the
 * {@link #create(MatsBrokerMonitor, MatsBrokerBrowseAndActions) create}-methods, and then reused.
 * <p>
 * The instance is thread-safe, as it is meant to be created once per JVM/broker, and be used by multiple threads!
 * 
 * @author Endre Stølsvik 2022-01-02 12:19 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsBrokerMonitorHtmlGui {

    static MatsBrokerMonitorHtmlGuiImpl create(MatsBrokerMonitor matsBrokerMonitor,
            MatsBrokerBrowseAndActions matsBrokerBrowseAndActions,
            List<? super MonitorAddition> monitorAdditions,
            MatsSerializer matsSerializer) {
        return new MatsBrokerMonitorHtmlGuiImpl(matsBrokerMonitor, matsBrokerBrowseAndActions, monitorAdditions,
                matsSerializer);
    }

    static MatsBrokerMonitorHtmlGuiImpl create(MatsBrokerMonitor matsBrokerMonitor,
            MatsBrokerBrowseAndActions matsBrokerBrowseAndActions) {
        return create(matsBrokerMonitor, matsBrokerBrowseAndActions, null, null);
    }

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a script-tag, or
     * included as a separate file (with hard caching).
     */
    void outputStyleSheet(Appendable out) throws IOException;

    /**
     * Note: The output from this method is static, it can be written directly to the HTML page in a style-tag, or
     * included as a separate file (with hard caching).
     */
    void outputJavaScript(Appendable out) throws IOException;

    /**
     * The embeddable HTML GUI - map this to GET, content type is <code>"text/html; charset=utf-8"</code>. This might
     * via the browser call back to {@link #json(Appendable, Map, String, AccessControl)} - which you also must mount at
     * (typically) the same URL (GETs go here, PUT, POST and DELETE goes to json).
     */
    void html(Appendable out, Map<String, String[]> requestParameters, AccessControl ac)
            throws IOException, AccessDeniedException;

    /**
     * The HTML GUI will invoke JSON-over-HTTP to the same URL it is located at - map this to PUT, POST and DELETE,
     * content type is <code>"application/json; charset=utf-8"</code>.
     * <p>
     * NOTICE: If you need to change the JSON Path, i.e. the path which this GUI employs to do "active" operations, you
     * can do so by setting the JS global variable "matsbm_json_path" when outputting the HTML, overriding the default
     * which is to use the current URL path (i.e. the same as the GUI is served on). They may be on the same path since
     * the HTML is served using GET, while the JSON uses PUT, POST and DELETE. (Read the 'matsbrokermonitor.js' file for
     * details.)
     */
    void json(Appendable out, Map<String, String[]> requestParameters, String requestBody, AccessControl ac)
            throws IOException, AccessDeniedException;

    /**
     * Top interface for the different "additions" that can be made to the Monitor GUI - you implement the extensions of
     * this interface based on what GUI features you need, and then add them to the Monitor GUI when you create it.
     * <p>
     * <b>Important security notice 1:</b> If you employ the {@link AccessControl AccessControl} interface made
     * available to the addition, and remove buttons or columns based on the user's access, you must ensure that the
     * backend also implements the security check: If you only remove the button or column from the HTML, the user can
     * still access the functionality by manually crafting the URL to the backend.
     * <p>
     * <b>Important security notice 2:</b> There is no escaping of the HTML that you return from the methods in the
     * sub-interfaces of this interface, so you must ensure that you HTML-escape all output values.
     * <p>
     * <b>Performance notice:</b> The additions are invoked "in-line" in the rendering. Whatever time it takes to render
     * the addition, will be added to the time it takes to render the page. Thus, if you e.g. invoke a slow REST service
     * to get information for the addition for each message in a "Browse Queue" view, this will slow down the rendering
     * of the page significantly (This GUI maxes out at 2000 messages, while ActiveMQ maxes out at 400). If you do need
     * to query some external resource per message (e.g. a Jira instance), you should probably make some kind of caching
     * on the backend to make this work well. An alternative is to let the addition just be an id'ed placeholder HTML,
     * and then, after the rendering is finished, add a bit of JavaScript to asynchronously fetch status for each of
     * those placeholders, replacing each of the placeholders with the actual status and maybe a link to the external
     * resource if relevant. This way, the page render won't be held back, and the user can start browsing the messages
     * while the status is fetched in the background.
     * <p>
     * <b>CSS-classes, and "external link"-icon:</b> You can make a button, or a link rendered exactly like a button (to
     * e.g. take the user to your logging system for this message's flow): There are a set of CSS classes that will make
     * your button, or link, look like the other buttons in the Mats Broker Monitor GUI pages' button rows. These
     * classes are "matsbm-button" paired with one of "matsbm-button-blue", "matsbm-button-green",
     * "matsbm-button-yellow", and "matsbm-button-red". The blue is the standard "action" color. In the button-row, if
     * you want your additions to align to the right side, you can prefix the added HTML with
     * "<code>&lt;span class='spacer'&gt;&lt;/span&gt;</code>". In addition, for links that send users to another page,
     * an "external link" icon is provided, which you can add as such:
     * "<code>&lt;span class='matsbm_external_link_icon'&gt;&lt;/span&gt;</code>".
     * <p>
     * <b>Tips for implementing "send to log system" button</b> (which probably is the most common and important
     * addition): You definitely want to add this as a link, not a JS button, as the user will typically want to open
     * the link in a new tab (i.e. right-click and select "Open in new tab" or "Open in new window", or middle-click)
     * You also want to add the "external link" icon, so that it is clear that this is a link to an external system.
     * Implementation wise, it might be a good idea to let the link first point back to the server hosting the Mats
     * Broker Monitor GUI, and then have the server construct the actual link to the logging system and redirect the
     * user - instead of constructing the addition links as pointing directly to the logging system. By doing this, you
     * are more in control of the URL, and can e.g. add some security tokens to the URL, and log the access to the link,
     * monitor usage, etc. You can then also go a bit creative for usability, and first query the logging backend to see
     * what are the first and last date-times for the messages in the flow (using the traceId), and then add those
     * date-times to the log system URL, so that the user is taken directly to the relevant time range in the logging
     * system. If you on the backend would like to have the full {@link MatsBrokerMessageRepresentation
     * MatsBrokerMessageRepresentation}, you can let the 'addition link' rendered in the GUI include two parameters:
     * {@link MatsBrokerDestination#getDestinationName() MatsBrokerDestination.getDestinationName()} and
     * {@link MatsBrokerMessageRepresentation#getMessageSystemId()
     * MatsBrokerMessageRepresentation.getMessageSystemId()}, which you on the backend can use to query for the
     * {@code MatsBrokerMessageRepresentation} using {@link MatsBrokerBrowseAndActions#examineMessage(String, String)
     * MatsBrokerBrowseAndActions.examineMessage(queueName, messageSystemId)}. Alternatively, you can just include the
     * {@link MatsBrokerMessageRepresentation#getTraceId() MatsBrokerMessageRepresentation.getTraceId()}, since this is
     * typically what you'd send to the logging system, querying for all log lines with that traceId.
     */
    interface MonitorAddition {
        /**
         * Representation of a Queue.
         */
        interface QueueRep {
            MatsBrokerDestination getMatsBrokerDestination();
        }

        /**
         * Representation of a Message.
         */
        interface MessageRep {
            MatsBrokerDestination getMatsBrokerDestination();

            MatsBrokerMessageRepresentation getMatsBrokerMessageRepresentation();
        }

        /**
         * Sent along with the different "get*HtmlFor" methods, to give context for the addition.
         */
        interface AdditionContext {
            /**
             * The {@link AccessControl}, i.e. which user, is accessing the GUI when this is invoked. This can e.g. be
             * used to determine if the user should see the addition or not.
             * <p>
             * <b>Important notice:</b> While this can be used to determine if the user should see the addition or not,
             * it is not a security mechanism per se: If you use the "HTML Addition" to create a link to some way to
             * e.g. modify all messages, you <b>must</b> implement the security check in the backend as well. Otherwise,
             * the user might just manually create the missing link that you removed from the HTML, and then call the
             * backend directly.
             * <p>
             * "Hack suggestion": You are guaranteed that the returned {@link AccessControl AccessControl} instance is
             * the same that you invoked the {@link MatsBrokerMonitorHtmlGui} GUI with. Thus, if you implement it, you
             * may add your own special methods to the implementation, and when you get the {@code AccessControl}
             * instance here in the {@code MonitorAddition} via this {@code AdditionContext}, you can cast it to your
             * own type and call your special methods. This thus provides a way to pass per-user/per-invocation context
             * to your implementation of the {@link MonitorAddition} interface. (Another solution would be to use a
             * {@code ThreadLocal})
             *
             * @return the {@link AccessControl AccessControl} for this user, which can be used to determine if the user
             *         should see this addition or not - <b>but note the security implication - not <i>seeing</i> the
             *         addition on the browser/client doesn't mean that the user can't <i>access</i> it: Such control
             *         must be implemented on the server side</b>, read more above in Javadoc.
             */
            AccessControl getAccessControl();
        }
    }

    /**
     * For adding buttons to the button row of the Broker Overview page.
     * <p>
     * <b>Important notice: These are fully raw HTML, with no escaping! You must HTML-escape all output values.</b>
     */
    interface BrokerOverviewAddition extends MonitorAddition {
        /**
         * @param ctx
         *            the context for this addition, including the {@link AccessControl} for this user.
         * @return the output wanted for the broker overview button row, raw HTML.
         */
        default String getButtonRowHtmlFor(AdditionContext ctx) {
            return null;
        }
    }

    /**
     * For adding buttons to the button row of the Browse Queue page, and new columns to the queue messages table.
     * <p>
     * <b>Important notice: These are fully raw HTML, with no escaping! You must escape all output values gotten from
     * the {@link QueueRep} or {@link MessageRep} objects!</b>
     */
    interface BrowseQueueAddition extends MonitorAddition {
        /**
         * @param ctx
         *            the context for this addition, including the {@link AccessControl} for this user.
         * @param queue
         *            the queue being browsed
         * @return the output wanted for this "browse queue" page's button row, raw HTML.
         */
        default String getButtonRowHtmlFor(AdditionContext ctx, QueueRep queue) {
            return null;
        }

        /**
         * @param ctx
         *            the context for this addition, including the {@link AccessControl} for this user.
         * @param queue
         *            the queue being browsed
         * @return the output wanted for this "browse queue" table column's heading, <b>which must include the
         *         <code>&lt;th&gt;</code> and <code>&lt;/th&gt;</code> HTML</b>. If null is returned, the entire column
         *         is elided.
         */
        default String getColumnHeadingHtmlFor(AdditionContext ctx, QueueRep queue) {
            return null;
        }

        /**
         * @param ctx
         *            the context for this addition, including the {@link AccessControl} for this user.
         * @param message
         *            the {@link MessageRep} being printed
         * @return the output wanted for this "browse queue" table cell, raw HTML, <b>which must look like
         *         "<code>&lt;td&gt;&lt;div&gt;</code><i>contents here</i><code>&lt;/div&gt;&lt;/td&gt;</code>" for the
         *         table cell</b> - remember both the td and the inner div! If you return <code>null</code> while the
         *         corresponding {@link #getColumnHeadingHtmlFor(AdditionContext, QueueRep)
         *         getColumnHeadingHtmlFor(QueueRep)} returned non-<code>null</code>, an empty cell will be output.
         */
        default String getCellHtmlFor(AdditionContext ctx, MessageRep message) {
            return null;
        }
    }

    /**
     * For adding buttons to the button row of the Examine Message page.
     * <p>
     * <b>Important notice: These are fully raw HTML, with no escaping! You must HTML-escape all output values gotten
     * from the {@link MessageRep} objects!</b>
     */
    interface ExamineMessageAddition extends MonitorAddition {
        /**
         * @param ctx
         *            the context for this addition, including the {@link AccessControl} for this user.
         * @param message
         *            the {@link MessageRep} being printed
         * @return the output wanted for this message, raw HTML.
         */
        default String getButtonRowHtmlFor(AdditionContext ctx, MessageRep message) {
            return null;
        }
    }

    /**
     * Controls access to the different views and operations in the Mats Broker Monitor GUI. This is meant to be
     * implemented by the application that embeds the Mats Broker Monitor GUI, and then passed to the GUI when it is
     * created. The methods in this interface should return <code>true</code> if the user is allowed to see the
     * overview, browse a queue, examine a message, reissue a message, mute a message, or delete a message,
     * respectively. For a quick way to allow all operations, see {@link #getAccessControlAllowAll(String)}.
     */
    interface AccessControl {
        default String username() {
            return "{unknown}";
        }

        default boolean overview() {
            return false;
        }

        default boolean browseQueue(String queueId) {
            return false;
        }

        default boolean examineMessage(String fromQueueId) {
            return false;
        }

        default boolean reissueMessage(String fromDeadLetterQueueId) {
            return false;
        }

        default boolean muteMessage(String fromDeadLetterQueueId) {
            return false;
        }

        default boolean deleteMessage(String fromQueueId) {
            return false;
        }
    }

    /**
     * Quick way to get an {@link AccessControl} instance which allow all views and operations. Relevant if the GUI is
     * embedded in a dev and/or ops area which obviously already is protected by authentication and authorization, where
     * all people that can see the GUI at all also should be allowed to see all information and do all operations.
     * 
     * @param username
     *            the username to use in the {@link AccessControl#username()} method.
     * @return an {@link AccessControl} which allows all operations.
     */
    static AccessControl getAccessControlAllowAll(String username) {
        return new AccessControl() {
            @Override
            public String username() {
                return username;
            }

            @Override
            public boolean overview() {
                return true;
            }

            @Override
            public boolean browseQueue(String queueId) {
                return true;
            }

            @Override
            public boolean examineMessage(String fromQueueId) {
                return true;
            }

            @Override
            public boolean reissueMessage(String fromDeadLetterQueueId) {
                return true;
            }

            @Override
            public boolean muteMessage(String fromDeadLetterQueueId) {
                return true;
            }

            @Override
            public boolean deleteMessage(String fromQueueId) {
                return true;
            }
        };
    }

    class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }
}
