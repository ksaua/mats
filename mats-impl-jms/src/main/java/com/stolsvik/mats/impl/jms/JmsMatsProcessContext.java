package com.stolsvik.mats.impl.jms;

import java.util.LinkedHashMap;
import java.util.List;

import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsStage;
import com.stolsvik.mats.MatsTrace;
import com.stolsvik.mats.exceptions.MatsBackendException;
import com.stolsvik.mats.impl.jms.JmsMatsInitiator.JmsMatsInitiate;
import com.stolsvik.mats.util.MatsStringSerializer;

/**
 * The JMS MATS implementation of {@link ProcessContext}. Instantiated for each incoming JMS message that is processed,
 * given to the {@link MatsStage}'s process lambda.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsProcessContext<S, R> implements ProcessContext<R>, JmsMatsStatics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsProcessContext.class);

    private final JmsMatsStage<?, ?, R> _matsStage;
    private final Session _jmsSession;
    private final MapMessage _mapMessage;
    private final MatsTrace _matsTrace;
    private final S _sto;

    public JmsMatsProcessContext(JmsMatsStage<?, ?, R> matsStage, Session jmsSession, MapMessage mapMessage,
            MatsTrace matsTrace, S sto) {
        _matsStage = matsStage;
        _jmsSession = jmsSession;
        _mapMessage = mapMessage;
        _matsTrace = matsTrace;
        _sto = sto;
    }

    private final LinkedHashMap<String, byte[]> _propsForNextMessage_binary = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> _propsForNextMessage_String = new LinkedHashMap<>();

    @Override
    public byte[] getBytes(String key) {
        try {
            return _mapMessage.getBytes(key);
        }
        catch (JMSException e) {
            throw new MatsBackendException("Got JMS problems when trying to context.getBinary(\"" + key + "\").", e);
        }
    }

    @Override
    public String getString(String key) {
        try {
            return _mapMessage.getString(key);
        }
        catch (JMSException e) {
            throw new MatsBackendException("Got JMS problems when trying to context.getString(\"" + key + "\").", e);
        }
    }

    @Override
    public void addBytes(String key, byte[] payload) {
        _propsForNextMessage_binary.put(key, payload);
    }

    @Override
    public void addString(String key, String payload) {
        _propsForNextMessage_String.put(key, payload);
    }

    @Override
    public MatsTrace getTrace() {
        return _matsTrace;
    }

    @Override
    public void request(String endpointId, Object requestDto) {
        // :: Add next stage as replyTo endpoint Id
        List<String> stack = _matsTrace.getCurrentCall().getStack();
        stack.add(0, _matsStage.getNextStageId());

        // :: Create next MatsTrace
        MatsStringSerializer matsStringSerializer = _matsStage
                .getParentEndpoint().getParentFactory().getMatsStringSerializer();
        MatsTrace requestMatsTrace = _matsTrace.addRequestCall(_matsStage.getStageId(), endpointId, matsStringSerializer
                .serializeObject(requestDto), stack, matsStringSerializer.serializeObject(_sto), null);

        // Pack it off
        sendMatsMessage(log, _jmsSession, _matsStage.getParentEndpoint().getParentFactory(), true, requestMatsTrace,
                _propsForNextMessage_binary, _propsForNextMessage_String, endpointId, "REQUEST");
    }

    @Override
    public void reply(Object replyDto) {
        // :: Pop the replyTo endpointId from the stack
        List<String> stack = _matsTrace.getCurrentCall().getStack();
        if (stack.size() == 0) {
            // This is OK, it is just like a normal java call where you do not use return value, e.g. map.put(k, v).
            log.info("Stage [" + _matsStage.getStageId() + " invoked context.reply(..), but there are no elements"
                    + " on the stack, hence no one to reply to. Dropping message.");
            return;
        }
        String replyToEndpointId = stack.remove(0);

        // :: Create next MatsTrace
        MatsStringSerializer matsStringSerializer = _matsStage
                .getParentEndpoint().getParentFactory().getMatsStringSerializer();
        MatsTrace replyMatsTrace = _matsTrace.addReplyCall(_matsStage.getStageId(), replyToEndpointId,
                matsStringSerializer.serializeObject(replyDto), stack);

        // Pack it off
        sendMatsMessage(log, _jmsSession, _matsStage.getParentEndpoint().getParentFactory(), true, replyMatsTrace,
                _propsForNextMessage_binary, _propsForNextMessage_String, replyToEndpointId, "REPLY");
    }

    @Override
    public void next(Object incomingDto) {
        // :: Assert that we have a next-stage
        if (_matsStage.getNextStageId() == null) {
            throw new IllegalStateException("Stage [" + _matsStage.getStageId()
                    + "] invoked context.next(..), but there is no next stage.");
        }

        // :: Use same stack, as this is a "sideways call", thus the replyStack is same as for previous stage
        List<String> stack = _matsTrace.getCurrentCall().getStack();

        // :: Create next (heh!) MatsTrace
        MatsStringSerializer matsStringSerializer = _matsStage
                .getParentEndpoint().getParentFactory().getMatsStringSerializer();
        MatsTrace nextMatsTrace = _matsTrace.addNextCall(_matsStage.getStageId(), _matsStage.getNextStageId(),
                matsStringSerializer.serializeObject(incomingDto), stack, matsStringSerializer.serializeObject(_sto));

        // Pack it off
        sendMatsMessage(log, _jmsSession, _matsStage.getParentEndpoint().getParentFactory(), true, nextMatsTrace,
                _propsForNextMessage_binary, _propsForNextMessage_String, _matsStage.getNextStageId(), "NEXT");
    }

    @Override
    public void initiate(InitiateLambda lambda) {
        lambda.initiate(new JmsMatsInitiate(_matsStage.getParentEndpoint().getParentFactory(), _jmsSession,
                _matsTrace.getTraceId(), _matsStage.getStageId()));
    }
}
