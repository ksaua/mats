package com.stolsvik.mats.websocket;

import java.io.IOException;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig.Builder;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stolsvik.mats.MatsEndpoint.DetachedProcessContext;
import com.stolsvik.mats.MatsEndpoint.MatsObject;
import com.stolsvik.mats.MatsEndpoint.ProcessContext;
import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;

/**
 * @author Endre Stølsvik 2019-11-28 12:17 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DefaultMatsSocketServer implements MatsSocketServer {
    private static final Logger log = LoggerFactory.getLogger(DefaultMatsSocketServer.class);

    private static final String REPLY_TERMINATOR_ID_PREFIX = "MatsSockets.replyHandler.";

    public static MatsSocketServer makeMatsSocketServer(ServerContainer serverContainer, MatsFactory matsFactory) {
        // TODO: "Escape" the AppName.
        String replyTerminatorId = REPLY_TERMINATOR_ID_PREFIX + matsFactory.getFactoryConfig().getAppName();
        ObjectMapper jackson = jacksonMapper();
        DefaultMatsSocketServer matsSocketServer = new DefaultMatsSocketServer(matsFactory, jackson, replyTerminatorId);

        log.info("Registering MatsSocket WebSocket endpoint");
        Configurator configurator = new Configurator() {
            @Override
            public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
                if (endpointClass != MatsWebSocketInstance.class) {
                    throw new AssertionError("Cannot create Endpoints of type [" + endpointClass.getName()
                            + "]");
                }
                // Generate a MatsSocketSessionId
                String matsSocketSessionId = randomId();
                log.info("Instantiating a MatsSocketEndpoint!");
                return (T) new MatsWebSocketInstance(matsSocketServer, jackson, matsSocketSessionId);
            }
        };
        try {
            serverContainer.addEndpoint(Builder.create(MatsWebSocketInstance.class, "/matssocket/json")
                    .subprotocols(Collections.singletonList("mats"))
                    .configurator(configurator)
                    .build());
        }
        catch (DeploymentException e) {
            throw new AssertionError("Could not register endpoint", e);
        }
        return matsSocketServer;
    }

    private static String randomId() {
        long long1 = ThreadLocalRandom.current().nextLong();
        long long2 = ThreadLocalRandom.current().nextLong();
        return Long.toString(long1, 36) + "_" + Long.toString(long2, 36);
    }

    private static class ReplyHandleStateDto {
        private final String _matsSocketSessionId;
        private final String _matsSocketEndpointId;
        private final String _replyEndpointId;
        private final String _correlationId;
        private final String _authorization;

        private ReplyHandleStateDto() {
            /* no-args constructor for Jackson */
            _matsSocketSessionId = null;
            _matsSocketEndpointId = null;
            _replyEndpointId = null;
            _correlationId = null;
            _authorization = null;
        }

        public ReplyHandleStateDto(String matsSocketSessionId, String matsSocketEndpointId, String replyEndpointId,
                String correlationId, String authorization) {
            _replyEndpointId = replyEndpointId;
            _matsSocketEndpointId = matsSocketEndpointId;
            _matsSocketSessionId = matsSocketSessionId;
            _correlationId = correlationId;
            _authorization = authorization;
        }
    }

    private final MatsFactory _matsFactory;
    private final ObjectMapper _jackson;
    private final String _replyTerminatorId;

    public DefaultMatsSocketServer(MatsFactory matsFactory, ObjectMapper jackson, String replyTerminatorId) {
        _matsFactory = matsFactory;
        _jackson = jackson;
        _replyTerminatorId = replyTerminatorId;

        // Register our Reply-handler (common on all nodes - need forwarding to correct node)
        // TODO: FORWARDING BETWEEN NODES!!
        matsFactory.terminator(replyTerminatorId, ReplyHandleStateDto.class, MatsObject.class,
                this::processReply);
    }

    private void processReply(ProcessContext<Void> processContext, ReplyHandleStateDto state,
            MatsObject incomingMsg) {
        Session session = _activeSessionByMatsSocketSessionId.get(state._matsSocketSessionId);
        // TODO: TOTALLY not do this!
        if (session == null) {
            log.error("Dropping message on floor for MatsSocketSessionId [" + state._matsSocketSessionId
                    + "]: No Session!");
            return;
        }

        MatsSocketEndpointRegistration registration = _matsSockets.get(state._matsSocketEndpointId);

        Object matsReply = incomingMsg.toClass(registration._matsReplyClass);

        Object msReply;
        if (registration._matsSocketEndpointReplyAdapter != null) {
            // TODO: Handle Principal.
            MatsSocketEndpointReplyContextImpl replyContext = new MatsSocketEndpointReplyContextImpl(
                    registration._matsSocketEndpointId, state._authorization, null, processContext);
            msReply = registration._matsSocketEndpointReplyAdapter.adaptReply(replyContext, matsReply);
        }
        else if (registration._matsReplyClass == registration._msReplyClass) {
            // -> Return same class
            msReply = matsReply;
        }
        else {
            throw new AssertionError("The class from Mats [" + registration._msReplyClass.getName()
                    + "] != the expected reply from MatsSocketEndpoint [" + registration._msReplyClass.getName()
                    + "].");
        }

        // Create Envelope
        MatsSocketMessageDto msReplyEnvelope = new MatsSocketMessageDto();
        msReplyEnvelope.t = "REPLY";
        msReplyEnvelope.eid = state._replyEndpointId;
        msReplyEnvelope.cid = state._correlationId;
        msReplyEnvelope.msg = msReply;

        // JSONify the MatsSocket Reply.
        String msReplyEnvelopeJson = null;
        try {
            msReplyEnvelopeJson = _jackson.writeValueAsString(msReplyEnvelope);
            log.info("Sending reply [" + msReplyEnvelopeJson + "]");
        }
        catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        // Send. TODO: Evidently needs sync?!
        synchronized (session) {
            session.getAsyncRemote().sendText(msReplyEnvelopeJson);
        }
    }

    private final ConcurrentHashMap<String, MatsSocketEndpointRegistration> _matsSockets = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Session> _activeSessionByMatsSocketSessionId = new ConcurrentHashMap<>();

    @Override
    public <I, MI, MR, R> MatsSocketEndpoint<I, MI, MR, R> matsSocketEndpoint(String matsSocketEndpointId,
            Class<I> msIncomingClass, Class<MR> matsReplyClass, Class<R> msReplyClass) {
        MatsSocketEndpointRegistration matsSocketRegistration = new MatsSocketEndpointRegistration(
                matsSocketEndpointId, msIncomingClass, matsReplyClass, msReplyClass);
        MatsSocketEndpointRegistration existing = _matsSockets.putIfAbsent(matsSocketEndpointId,
                matsSocketRegistration);
        // Assert that there was no existing mapping
        if (existing != null) {
            // -> There was existing mapping - shall not happen.
            throw new IllegalStateException("Cannot register a MatsSocket onto an EndpointId which already is"
                    + " taken, existing: [" + existing._matsSocketEndpointIncomingForwarder + "].");
        }
        return matsSocketRegistration;
    }

    private static class MatsSocketEndpointRegistration<I, MI, MR, R> implements MatsSocketEndpoint<I, MI, MR, R> {
        private final String _matsSocketEndpointId;
        private final Class<I> _msIncomingClass;
        private final Class<MR> _matsReplyClass;
        private final Class<R> _msReplyClass;

        public MatsSocketEndpointRegistration(String matsSocketEndpointId, Class<I> msIncomingClass,
                Class<MR> matsReplyClass, Class<R> msReplyClass) {
            _matsSocketEndpointId = matsSocketEndpointId;
            _msIncomingClass = msIncomingClass;
            _matsReplyClass = matsReplyClass;
            _msReplyClass = msReplyClass;
        }

        private volatile MatsSocketEndpointIncomingForwarder _matsSocketEndpointIncomingForwarder;
        private volatile MatsSocketEndpointReplyAdapter<MR, R> _matsSocketEndpointReplyAdapter;

        @Override
        public void incomingForwarder(
                MatsSocketEndpointIncomingForwarder<I, MI, R> matsSocketEndpointIncomingForwarder) {
            _matsSocketEndpointIncomingForwarder = matsSocketEndpointIncomingForwarder;
        }

        @Override
        public void replyAdapter(MatsSocketEndpointReplyAdapter<MR, R> matsSocketEndpointReplyAdapter) {
            _matsSocketEndpointReplyAdapter = matsSocketEndpointReplyAdapter;
        }
    }

    public static class MatsWebSocketInstance<I, MR, R> extends Endpoint {
        private final DefaultMatsSocketServer _matsSocketServer;
        private final ObjectMapper _jackson;
        private final String _matsSocketSessionId;

        public MatsWebSocketInstance(DefaultMatsSocketServer matsSocketServer,
                ObjectMapper jackson, String matsSocketSessionId) {
            _matsSocketServer = matsSocketServer;
            _jackson = jackson;
            _matsSocketSessionId = matsSocketSessionId;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            log.info("WebSocket opened, session:" + session.getId() + ", endpointConfig:" + config + ", this:"
                    + id(this));
            session.setMaxIdleTimeout(20_000);
            session.addMessageHandler(new MatsWsMessageHandler(this, _jackson, session));
            // TODO: NOTIFY THE FORWARDING MECHANISM THAT WE NOW HAVE THIS MatsSocketSession
            // Add Session to our active-map
            _matsSocketServer._activeSessionByMatsSocketSessionId.put(_matsSocketSessionId, session);
        }

        @Override
        public void onError(Session session, Throwable thr) {
            log.info("WebSocket @OnError, session:" + session.getId() + ", this:" + id(this), thr);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            log.info("WebSocket @OnClose, session:" + session.getId() + ", reason:" + closeReason.getReasonPhrase()
                    + ", this:" + id(this));
            // TODO: NOTIFY THE FORWARDING MECHANISM THAT WE NO LONGER HAVE THIS MatsSocketSession
            // Remove Session from our active-map
            _matsSocketServer._activeSessionByMatsSocketSessionId.remove(_matsSocketSessionId);
        }
    }

    private static class MatsWsMessageHandler implements Whole<String> {
        private static final JavaType LIST_OF_MSG_TYPE = TypeFactory.defaultInstance()
                .constructType(new TypeReference<List<MatsSocketMessageDto>>() {
                });

        private final MatsWebSocketInstance _matsWebSocketInstance;
        private final ObjectMapper _jackson;
        private final Session _session;

        // Derived
        private final DefaultMatsSocketServer _matsSocketServer;

        public MatsWsMessageHandler(
                MatsWebSocketInstance matsWebSocketInstance, ObjectMapper jackson, Session session) {
            _matsWebSocketInstance = matsWebSocketInstance;
            _jackson = jackson;
            _session = session;

            // Derived
            _matsSocketServer = _matsWebSocketInstance._matsSocketServer;
        }

        @Override
        public void onMessage(String message) {
            log.info("WebSocket received message:" + message + ", session:" + _session.getId() + ", this:" + id(this));

            List<MatsSocketMessageDto> msgs;
            try {
                msgs = _jackson.readValue(message, LIST_OF_MSG_TYPE);
            }
            catch (JsonProcessingException e) {
                // TODO: Handle parse exceptions.
                throw new AssertionError("Damn", e);
            }

            log.info("Messages: " + msgs);
            for (MatsSocketMessageDto envelope : msgs) {
                if ("SEND".equals(envelope.t)) {
                    String eid = envelope.eid;
                    log.info("  \\- SEND to:[" + eid + "], envelope:[" + envelope.msg + "].");
                    MatsSocketEndpointRegistration matsSocketEndpointRegistration = getMatsSocketRegistration(eid);
                    MatsSocketEndpointIncomingForwarder matsSocketEndpointIncomingForwarder = matsSocketEndpointRegistration._matsSocketEndpointIncomingForwarder;
                    log.info("MatsSocketEndpointHandler for [" + eid + "]: " + matsSocketEndpointIncomingForwarder);

                    Object msg = deserialize((String) envelope.msg, matsSocketEndpointRegistration._msIncomingClass);
                    MatsSocketEndpointRequestContextImpl matsSocketContext = new MatsSocketEndpointRequestContextImpl(
                            _matsSocketServer,
                            matsSocketEndpointRegistration,
                            _matsWebSocketInstance._matsSocketSessionId,
                            envelope, "DummyAuth",
                            null);
                    matsSocketEndpointIncomingForwarder.forwardIncoming(matsSocketContext, msg);
                }
                else if ("REQUEST".equals(envelope.t)) {
                    String eid = envelope.eid;
                    String reid = envelope.reid;
                    log.info("  \\- REQUEST to:[" + eid + "], reply:[" + reid + "], envelope:[" + envelope.msg + "].");
                    MatsSocketEndpointRegistration matsSocketEndpointRegistration = getMatsSocketRegistration(eid);
                    MatsSocketEndpointIncomingForwarder matsSocketEndpointIncomingForwarder = matsSocketEndpointRegistration._matsSocketEndpointIncomingForwarder;
                    log.info("MatsSocketEndpointHandler for [" + eid + "]: " + matsSocketEndpointIncomingForwarder);

                    Object msg = deserialize((String) envelope.msg, matsSocketEndpointRegistration._msIncomingClass);
                    MatsSocketEndpointRequestContextImpl matsSocketContext = new MatsSocketEndpointRequestContextImpl(
                            _matsSocketServer,
                            matsSocketEndpointRegistration,
                            _matsWebSocketInstance._matsSocketSessionId,
                            envelope, "DummyAuth",
                            null);
                    matsSocketEndpointIncomingForwarder.forwardIncoming(matsSocketContext, msg);
                }
            }
        }

        private <T> T deserialize(String serialized, Class<T> clazz) {
            try {
                return _jackson.readValue(serialized, clazz);
            }
            catch (JsonProcessingException e) {
                // TODO: Handle parse exceptions.
                throw new AssertionError("Damn", e);
            }
        }

        private MatsSocketEndpointRegistration getMatsSocketRegistration(String eid) {
            MatsSocketEndpointRegistration matsSocketRegistration = _matsSocketServer._matsSockets.get(eid);
            log.info("MatsSocketRegistration for [" + eid + "]: " + matsSocketRegistration);
            if (matsSocketRegistration == null) {
                // TODO / SECURITY: Better handling AND JSON/HTML ENCODING!!
                throw new IllegalArgumentException("Cannot find MatsSocketRegistration for [" + eid + "]");
            }
            return matsSocketRegistration;
        }
    }

    private static class MatsSocketMessageDto {
        String hos; // Host OS, e.g. "iOS,v13.2", "Android,vKitKat.4.4", "Chrome,v123:Windows,vXP",
        // "Java,v11.03:Windows,v2019"
        String an; // AppName
        String av; // AppVersion

        String auth; // Authorization header

        String t; // Type
        String st; // "SubType": AUTH_FAIL:"enum", EXCEPTION:Classname, MSGERROR:"enum"
        String desc; // Description of "st" of failure, exception message, multiline, may include stacktrace if authz.
        String inMsg; // On MSGERROR: Incoming Message, BASE64 encoded.

        long ts; // TimeStamp
        String tid; // TraceId
        String sid; // SessionId
        String cid; // CorrelationId
        String eid; // target MatsSocketEndpointId
        String reid; // reply MatsSocketEndpointId

        @JsonDeserialize(using = MessageToStringDeserializer.class)
        Object msg; // Message, JSON

        DebugDto dbg; // Debug
    }

    private static class Message {
        String msg; // Just to get the entire JSON right here.
    }

    private static class DebugDto {
        String d; // Description
        List<LogLineDto> l; // Log - this will be appended to if debugging is active.
    }

    private static class LogLineDto {
        long ts; // TimeStamp
        String s; // System: "MatsSockets", "Mats", "MS SQL" or similar.
        String hos; // Host OS, e.g. "iOS,v13.2", "Android,vKitKat.4.4", "Chrome,v123:Windows,vXP",
                    // "Java,v11.03:Windows,v2019"
        String an; // AppName
        String av; // AppVersion
        String t; // Thread name
        int level; // 0=TRACE, 1=DEBUG, 2=INFO, 3=WARN, 4=ERROR
        String m; // Message
        String x; // Exception if any, null otherwise.
        Map<String, String> mdc; // The MDC
    }

    private static class MatsSocketEndpointRequestContextImpl implements MatsSocketEndpointRequestContext {
        private final DefaultMatsSocketServer _matsSocketServer;
        private final MatsSocketEndpointRegistration _matsSocketEndpointRegistration;

        private final String _matsSocketSessionId;

        private final MatsSocketMessageDto _envelope;

        private final String _authorization;
        private final Principal _principal;

        public MatsSocketEndpointRequestContextImpl(DefaultMatsSocketServer matsSocketServer,
                MatsSocketEndpointRegistration matsSocketEndpointRegistration, String matsSocketSessionId,
                MatsSocketMessageDto envelope, String authorization, Principal principal) {
            _matsSocketServer = matsSocketServer;
            _matsSocketEndpointRegistration = matsSocketEndpointRegistration;
            _matsSocketSessionId = matsSocketSessionId;
            _envelope = envelope;
            _authorization = authorization;
            _principal = principal;
        }

        @Override
        public String getMatsSocketEndpointId() {
            return _envelope.eid;
        }

        @Override
        public String getAuthorization() {
            return _authorization;
        }

        @Override
        public Principal getPrincipal() {
            return _principal;
        }

        @Override
        public void forwardInteractiveUnreliable(Object matsMessage) {
            _matsSocketServer._matsFactory.getDefaultInitiator().initiateUnchecked(msg -> {
                msg.to(_envelope.eid)
                        .from("MatsSocketEndpoint." + _envelope.eid)
                        .traceId(_envelope.tid);
                if (isRequest()) {
                    ReplyHandleStateDto sto = new ReplyHandleStateDto(_matsSocketSessionId,
                            _matsSocketEndpointRegistration._matsSocketEndpointId, _envelope.reid,
                            _envelope.cid, getAuthorization());
                    msg.replyTo(_matsSocketServer._replyTerminatorId, sto);
                    msg.request(matsMessage);
                }
                else {
                    msg.send(matsMessage);
                }
            });

        }

        @Override
        public void initiate(InitiateLambda msg) {
            _matsSocketServer._matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                init.from("MatsSocketEndpoint." + _envelope.eid)
                        .traceId(_envelope.tid);
                if (isRequest()) {
                    ReplyHandleStateDto sto = new ReplyHandleStateDto(_matsSocketSessionId,
                            _matsSocketEndpointRegistration._matsSocketEndpointId, _envelope.reid,
                            _envelope.cid, getAuthorization());
                    init.replyTo(_matsSocketServer._replyTerminatorId, sto);
                    msg.initiate(init);
                }
                else {
                    msg.initiate(init);
                }
            });
        }

        @Override
        public void initiateRaw(InitiateLambda msg) {

        }

        @Override
        public boolean isRequest() {
            return _envelope.t.equals("REQUEST");
        }

        @Override
        public void reply(Object matsSocketReplyMessage) {

        }
    }

    private static class MatsSocketEndpointReplyContextImpl implements MatsSocketEndpointReplyContext {
        private final String _matsSocketEndpointId;
        private final String _authorization;
        private final Principal _principal;
        private final DetachedProcessContext _detachedProcessContext;

        public MatsSocketEndpointReplyContextImpl(String matsSocketEndpointId, String authorization,
                Principal principal, DetachedProcessContext detachedProcessContext) {
            _matsSocketEndpointId = matsSocketEndpointId;
            _authorization = authorization;
            _principal = principal;
            _detachedProcessContext = detachedProcessContext;
        }

        @Override
        public String getMatsSocketEndpointId() {
            return _matsSocketEndpointId;
        }

        @Override
        public String getAuthorization() {
            return _authorization;
        }

        @Override
        public Principal getPrincipal() {
            return _principal;
        }

        @Override
        public DetachedProcessContext getMatsContext() {
            return _detachedProcessContext;
        }
    }

    private static ObjectMapper jacksonMapper() {
        // NOTE: This is stolen directly from MatsSerializer_DefaultJson.
        ObjectMapper mapper = new ObjectMapper();

        // Read and write any access modifier fields (e.g. private)
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

        // Drop nulls
        mapper.setSerializationInclusion(Include.NON_NULL);

        // If props are in JSON that aren't in Java DTO, do not fail.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Write e.g. Dates as "1975-03-11" instead of timestamp, and instead of array-of-ints [1975, 3, 11].
        // Uses ISO8601 with milliseconds and timezone (if present).
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Optional, OptionalLong, OptionalDouble
        mapper.registerModule(new Jdk8Module());

        return mapper;
    }

    private static class MessageToStringDeserializer extends JsonDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            // TODO / OPTIMIZE: Find faster way to get as String, avoiding tons of JsonNode objects.
            // Trick must be to just consume from the START_OBJECT to the /corresponding/ END_OBJECT.
            return p.readValueAsTree().toString();
        }
    }

    private static String id(Object x) {
        return x.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(x));
    }
}
