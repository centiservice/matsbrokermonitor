package io.mats3.matsbrokermonitor.jms;

/**
 * @author Endre Stølsvik 2022-01-16 23:24 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface Statics {
    // COPIED FROM JmsMatsFactory
    // JMS Properties put on the JMSMessage via set[String|Long|Boolean]Property(..)
    String JMS_MSG_PROP_TRACE_ID = "mats_TraceId"; // String
    String JMS_MSG_PROP_MATS_MESSAGE_ID = "mats_MsgId"; // String
    String JMS_MSG_PROP_DISPATCH_TYPE = "mats_DispatchType"; // String
    String JMS_MSG_PROP_MESSAGE_TYPE = "mats_MsgType"; // String
    String JMS_MSG_PROP_ENVELOPE_SIZE = "mats_EnvSize"; // Long
    String JMS_MSG_PROP_FROM = "mats_From"; // String
    String JMS_MSG_PROP_TO = "mats_To"; // String
    String JMS_MSG_PROP_AUDIT = "mats_Audit"; // Boolean

}
