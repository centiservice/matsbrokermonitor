package io.mats3.matsbrokermonitor.jms;

import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.matsbrokermonitor.api.MatsBrokerBrowseAndActions;

/**
 * @author Endre Stølsvik 2022-01-15 23:04 - http://stolsvik.com/, endre@stolsvik.com
 */
public class JmsMatsBrokerBrowseAndActions implements MatsBrokerBrowseAndActions, Statics {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsBrokerBrowseAndActions.class);

    private final ConnectionFactory _connectionFactory;
    private final String _matsTraceKey;

    public static JmsMatsBrokerBrowseAndActions create(ConnectionFactory connectionFactory, String matsTraceKey) {
        return new JmsMatsBrokerBrowseAndActions(connectionFactory, matsTraceKey);
    }

    public static JmsMatsBrokerBrowseAndActions create(ConnectionFactory connectionFactory) {
        return new JmsMatsBrokerBrowseAndActions(connectionFactory, "mats:trace");
    }

    private JmsMatsBrokerBrowseAndActions(ConnectionFactory connectionFactory, String matsTraceKey) {
        _connectionFactory = connectionFactory;
        _matsTraceKey = matsTraceKey;
    }

    @Override
    public void start() {
        /* nothing to do */
    }

    @Override
    public void close() {
        /* nothing to do */
    }

    @Override
    public MatsBrokerMessageIterable browseQueue(String queueId)
            throws BrokerIOException {
        return browse_internal(queueId, null);
    }

    @Override
    public Optional<MatsBrokerMessageRepresentation> examineMessage(String queueId, String messageSystemId)
            throws BrokerIOException {
        if (messageSystemId == null) {
            throw new NullPointerException("messageSystemId");
        }
        try (MatsBrokerMessageIterableImpl iterable = browse_internal(queueId,
                "JMSMessageID = '" + messageSystemId + "'")) {
            Iterator<MatsBrokerMessageRepresentation> iter = iterable.iterator();
            if (!iter.hasNext()) {
                return Optional.empty();
            }
            return Optional.of(iter.next());
        }
    }

    private MatsBrokerMessageIterableImpl browse_internal(String queueId, String jmsMessageSelector) {
        if (queueId == null) {
            throw new NullPointerException("queueId");
        }
        Connection connection = null;
        try {
            connection = _connectionFactory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.DUPS_OK_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueId);
            QueueBrowser browser = session.createBrowser(queue, jmsMessageSelector);
            @SuppressWarnings("unchecked")
            Enumeration<Message> messageEnumeration = (Enumeration<Message>) browser.getEnumeration();
            return new MatsBrokerMessageIterableImpl(connection, _matsTraceKey, messageEnumeration);
        }
        catch (JMSException e) {
            JMSException suppressed = null;
            if (connection != null) {
                try {
                    connection.close();
                }
                catch (JMSException ex) {
                    suppressed = ex;
                }
            }
            BrokerIOException brokerIOException = new BrokerIOException("Problems talking with broker.", e);
            if (suppressed != null) {
                brokerIOException.addSuppressed(suppressed);
            }
            throw brokerIOException;
        }
    }

    private static class MatsBrokerMessageIterableImpl implements MatsBrokerMessageIterable {
        private static final Logger log = LoggerFactory.getLogger(MatsBrokerMessageIterableImpl.class);
        private final Connection _connection;
        private final String _matsTraceKey;
        private final Enumeration<Message> _messageEnumeration;

        public MatsBrokerMessageIterableImpl(Connection connection, String matsTraceKey,
                Enumeration<Message> messageEnumeration) {
            _connection = connection;
            _matsTraceKey = matsTraceKey;
            _messageEnumeration = messageEnumeration;
        }

        @Override
        public void close() {
            try {
                _connection.close();
            }
            catch (JMSException e) {
                log.warn("Couldn't close JMS Connection after browsing. Ignoring.", e);
            }
        }

        @Override
        public Iterator<MatsBrokerMessageRepresentation> iterator() {
            return new Iterator<MatsBrokerMessageRepresentation>() {
                @Override
                public boolean hasNext() {
                    return _messageEnumeration.hasMoreElements();
                }

                @Override
                public MatsBrokerMessageRepresentation next() {
                    return jmsMessageToMatsRepresentation(_messageEnumeration.nextElement(), _matsTraceKey);
                }
            };
        }
    }

    private static MatsBrokerMessageRepresentation jmsMessageToMatsRepresentation(Message message, String matsTraceKey)
            throws BrokerIOException {
        try {
            String messageSystemId = message.getJMSMessageID();
            long timestamp = message.getJMSTimestamp();
            String traceId = message.getStringProperty(JMS_MSG_PROP_TRACE_ID);
            String messageType = message.getStringProperty(JMS_MSG_PROP_MESSAGE_TYPE);
            String fromStageId = message.getStringProperty(JMS_MSG_PROP_FROM);
            String initializingApp = message.getStringProperty(JMS_MSG_PROP_INITIALIZING_APP);
            String initatorId = message.getStringProperty(JMS_MSG_PROP_INITIATOR_ID);
            // Relevant for Global DLQ, where the original id is now effectively lost
            String toStageId = message.getStringProperty(JMS_MSG_PROP_TO);
            boolean persistent = message.getJMSDeliveryMode() == DeliveryMode.PERSISTENT;
            boolean interactive = message.getJMSPriority() > 4; // Mats-JMS uses 9 for "interactive"
            long expirationTimestamp = message.getJMSExpiration();

            // Handle MatsTrace
            byte[] matsTraceBytes = null;
            String matsTraceMeta = null;
            if (message instanceof MapMessage) {
                MapMessage mm = (MapMessage) message;
                matsTraceBytes = mm.getBytes(matsTraceKey);
                matsTraceMeta = mm.getString(matsTraceKey + ":meta");
            }
            return new JmsMatsBrokerMessageRepresentationImpl(message, messageSystemId, timestamp, traceId, messageType,
                    fromStageId, initializingApp, initatorId, toStageId, persistent, interactive, expirationTimestamp,
                    matsTraceBytes, matsTraceMeta);
        }
        catch (JMSException e) {
            throw new BrokerIOException("Couldn't fetch data from JMS Message", e);
        }

    }

    private static class JmsMatsBrokerMessageRepresentationImpl implements MatsBrokerMessageRepresentation {
        private final Message _jmsMessage;

        private final String _messageSystemId;
        private final long _timestamp;
        private final String _traceId;
        private final String _messageType;
        private final String _fromStageId;
        private final String _initializingApp;
        private final String _initiatorId;
        private final String _toStageId;
        private final boolean _persistent;
        private final boolean _interactive;
        private final long _expirationTimestamp;
        private final byte[] _matsTraceBytes; // nullable
        private final String _matsTraceMeta; // nullable

        public JmsMatsBrokerMessageRepresentationImpl(Message jmsMessage, String messageSystemId, long timestamp,
                String traceId, String messageType, String fromStageId, String initializingApp, String initiatorId,
                String toStageId, boolean persistent, boolean interactive, long expirationTimestamp,
                byte[] matsTraceBytes, String matsTraceMeta) {
            _jmsMessage = jmsMessage;

            _messageSystemId = messageSystemId;
            _timestamp = timestamp;
            _traceId = traceId;
            _messageType = messageType;
            _fromStageId = fromStageId;
            _initializingApp = initializingApp;
            _initiatorId = initiatorId;
            _toStageId = toStageId;
            _persistent = persistent;
            _interactive = interactive;
            _expirationTimestamp = expirationTimestamp;

            // These are nullable
            _matsTraceBytes = matsTraceBytes;
            _matsTraceMeta = matsTraceMeta;
        }

        @Override
        public String getMessageSystemId() {
            return _messageSystemId;
        }

        @Override
        public long getTimestamp() {
            return _timestamp;
        }

        @Override
        public String getTraceId() {
            return _traceId;
        }

        @Override
        public String getMessageType() {
            return _messageType;
        }

        @Override
        public String getFromStageId() {
            return _fromStageId;
        }

        @Override
        public String getInitializingApp() {
            return _initializingApp;
        }

        @Override
        public String getInitiatorId() {
            return _initiatorId;
        }

        @Override
        public String getToStageId() {
            return _toStageId;
        }

        @Override
        public boolean isPersistent() {
            return _persistent;
        }

        @Override
        public boolean isInteractive() {
            return _interactive;
        }

        @Override
        public long getExpirationTimestamp() {
            return _expirationTimestamp;
        }

        @Override
        public Optional<byte[]> getMatsTraceBytes() {
            return Optional.ofNullable(_matsTraceBytes);
        }

        @Override
        public Optional<String> getMatsTraceMeta() {
            return Optional.ofNullable(_matsTraceMeta);
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "{JMS Message: " + _jmsMessage + "}";
        }
    }

    @Override
    public List<String> deleteMessages(String queueId, List<String> messageSystemIds) {
        // TODO: Implement!
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public int deleteAllMessages(String destinationId) {
        // TODO: Implement!
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public List<String> moveMessages(String sourceQueueId, String targetQueueId, List<String> messageSystemIds) {
        // TODO: Implement!
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public int moveAllMessages(String sourceQueueId, String targetQueueId) {
        // TODO: Implement!
        throw new IllegalStateException("Not implemented");
    }
}
