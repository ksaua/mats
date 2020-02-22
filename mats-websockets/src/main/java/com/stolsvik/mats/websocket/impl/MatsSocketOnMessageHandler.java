package com.stolsvik.mats.websocket.impl;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.MessageHandler.Whole;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.stolsvik.mats.MatsInitiator.InitiateLambda;
import com.stolsvik.mats.MatsInitiator.MatsBackendRuntimeException;
import com.stolsvik.mats.MatsInitiator.MatsMessageSendRuntimeException;
import com.stolsvik.mats.websocket.AuthenticationPlugin.AuthenticationContext;
import com.stolsvik.mats.websocket.AuthenticationPlugin.AuthenticationResult;
import com.stolsvik.mats.websocket.AuthenticationPlugin.SessionAuthenticator;
import com.stolsvik.mats.websocket.ClusterStoreAndForward.DataAccessException;
import com.stolsvik.mats.websocket.ClusterStoreAndForward.WrongUserException;
import com.stolsvik.mats.websocket.MatsSocketServer.IncomingAuthorizationAndAdapter;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketCloseCodes;
import com.stolsvik.mats.websocket.MatsSocketServer.MatsSocketEndpointRequestContext;
import com.stolsvik.mats.websocket.impl.AuthenticationContextImpl.AuthenticationResult_Authenticated;
import com.stolsvik.mats.websocket.impl.AuthenticationContextImpl.AuthenticationResult_StillValid;
import com.stolsvik.mats.websocket.impl.DefaultMatsSocketServer.MatsSocketEndpointRegistration;
import com.stolsvik.mats.websocket.impl.DefaultMatsSocketServer.ReplyHandleStateDto;

/**
 * @author Endre Stølsvik 2019-11-28 12:17 - http://stolsvik.com/, endre@stolsvik.com
 */
class MatsSocketOnMessageHandler implements Whole<String>, MatsSocketStatics {
    private static final Logger log = LoggerFactory.getLogger(MatsSocketOnMessageHandler.class);

    private Session _webSocketSession; // Non-final to be able to null out upon close.
    private String _connectionId;  // Non-final to be able to null out upon close.
    private final SessionAuthenticator _sessionAuthenticator;

    // Derived
    private Basic _webSocketBasicRemote; // Non-final to be able to null out upon close.
    private final DefaultMatsSocketServer _matsSocketServer;
    private final AuthenticationContext _authenticationContext;
    private final ObjectReader _envelopeListObjectReader;
    private final ObjectWriter _envelopeListObjectWriter;

    // Set
    private String _matsSocketSessionId;
    private String _authorization;
    private Principal _principal;
    private String _userId;

    private String _clientLibAndVersion;
    private String _appNameAndVersion;

    MatsSocketOnMessageHandler(DefaultMatsSocketServer matsSocketServer, Session webSocketSession,
            HandshakeRequest handshakeRequest, SessionAuthenticator sessionAuthenticator) {
        _webSocketSession = webSocketSession;
        _connectionId = webSocketSession.getId() + "_" + DefaultMatsSocketServer.rnd(10);
        _sessionAuthenticator = sessionAuthenticator;

        // Derived
        _webSocketBasicRemote = _webSocketSession.getBasicRemote();
        _matsSocketServer = matsSocketServer;
        _authenticationContext = new AuthenticationContextImpl(handshakeRequest, _webSocketSession);
        _envelopeListObjectReader = _matsSocketServer.getEnvelopeListObjectReader();
        _envelopeListObjectWriter = _matsSocketServer.getEnvelopeListObjectWriter();
    }

    Session getWebSocketSession() {
        return _webSocketSession;
    }

    void webSocketSendText(String text) throws IOException {
        synchronized (_webSocketBasicRemote) {
            _webSocketBasicRemote.sendText(text);
        }
    }

    /**
     * NOTE: There can <i>potentially</i> be multiple instances of {@link MatsSocketOnMessageHandler} with the same Id
     * if we're caught by bad asyncness wrt. one connection dropping and the client immediately reconnecting. The two
     * {@link MatsSocketOnMessageHandler}s would then hey would then have different {@link #getWebSocketSession()
     * WebSocketSessions}, i.e. differing actual connections. One of them would soon realize that is was closed. <b>This
     * Id together with {@link #getConnectionId()} is unique</b>.
     *
     * @return the MatsSocketSessionId that this {@link MatsSocketOnMessageHandler} instance refers to.
     */
    String getId() {
        return _matsSocketSessionId;
    }

    /**
     * NOTE: Read JavaDoc of {@link #getId} to understand why this Id is of interest.
     */
    String getConnectionId() {
        return _connectionId;
    }

    @Override
    public void onMessage(String message) {
        try { // try-finally: MDC.clear();
            long clientMessageReceivedTimestamp = System.currentTimeMillis();
            if (_matsSocketSessionId != null) {
                MDC.put(MDC_SESSION_ID, _matsSocketSessionId);
                MDC.put(MDC_PRINCIPAL_NAME, _principal.getName());
                MDC.put(MDC_USER_ID, _userId);
            }
            if (_clientLibAndVersion != null) {
                MDC.put(MDC_CLIENT_LIB_AND_VERSIONS, _clientLibAndVersion);
                MDC.put(MDC_CLIENT_APP_NAME_AND_VERSION, _appNameAndVersion);
            }
            log.info("WebSocket received message [" + message + "] on MatsSocketSessionId [" + _matsSocketSessionId
                    + "], WebSocket SessionId:" + _webSocketSession.getId() + ", this:"
                    + DefaultMatsSocketServer.id(this));

            List<MatsSocketEnvelopeDto> envelopes;
            try {
                envelopes = _envelopeListObjectReader.readValue(message);
            }
            catch (IOException e) {
                // TODO: Handle parse exceptions.
                throw new AssertionError("Parse exception", e);
            }

            log.info("Messages: " + envelopes);
            boolean shouldNotifyAboutExistingMessages = false;
            boolean sessionLost = false;

            // :: 1. Look for Authorization header in any of the messages
            // NOTE! Authorization header can come with ANY message!
            for (MatsSocketEnvelopeDto envelope : envelopes) {
                // ?: Pick out any Authorization header, i.e. the auth-string - it can come in any message.
                if (envelope.auth != null) {
                    // -> Yes, there was an authorization header sent along with this message
                    _authorization = envelope.auth;
                    log.info("Found authorization header in message of type [" + (envelope.st != null ? envelope.t + ':'
                            + envelope.st : envelope.t) + "]");
                    // No 'break', as we want to go through all messages and find the latest.
                }
            }

            // AUTHENTICATION! On every pipeline of messages, we re-evaluate authentication

            // :: 2. do we have Authorization header? (I.e. it must sent along in the very first pipeline..)
            if (_authorization == null) {
                log.error("We have not got Authorization header!");
                closeWithPolicyViolation("Missing Authorization header");
                return;
            }

            // :: 3. Evaluate Authentication by Authorization header
            boolean authenticationOk = doAuthentication();
            // ?: Did this go OK?
            if (!authenticationOk) {
                // -> No, not OK - doAuthentication() has already closed session and websocket and the lot.
                return;
            }

            // :: 4. look for a HELLO message
            // (should be first/alone, but we will reply to it immediately even if part of pipeline).
            for (Iterator<MatsSocketEnvelopeDto> it = envelopes.iterator(); it.hasNext();) {
                MatsSocketEnvelopeDto envelope = it.next();
                if ("HELLO".equals(envelope.t)) {
                    try { // try-finally: MDC.remove(..)
                        MDC.put(MDC_MESSAGE_TYPE, (envelope.st != null ? envelope.t + ':' + envelope.st : envelope.t));
                        // Remove this HELLO envelope from pipeline
                        it.remove();
                        // Handle the HELLO
                        boolean handleHelloOk = handleHello(clientMessageReceivedTimestamp, envelope);
                        // ?: Did the HELLO go OK?
                        if (!handleHelloOk) {
                            // -> No, not OK - handleHello(..) has already closed session and websocket and the lot.
                            return;
                        }
                        // ?: Did the client expect existing session with existing sessionId, but got different?
                        if ("EXPECT_EXISTING".equals(envelope.st) && (!_matsSocketSessionId.equals(envelope.sid))) {
                            // -> Yes, session lost, so then we drop any pipelined REQUEST/SEND with SESSION_LOST
                            // (done in the loop below)
                            sessionLost = true;
                        }

                        // Notify client about "new" (as in existing) messages, just in case there are any.
                        shouldNotifyAboutExistingMessages = true;
                    }
                    finally {
                        MDC.remove(MDC_MESSAGE_TYPE);
                    }
                    break;
                }
            }

            // :: 5. Assert state: All present: SessionId, Principal and userId.
            if ((_matsSocketSessionId == null) || (_principal == null) || (_userId == null)) {
                closeWithPolicyViolation("Illegal state at checkpoint A.");
                return;
            }

            // :: 6. Now go through and handle all the rest of the messages
            List<MatsSocketEnvelopeDto> replyEnvelopes = new ArrayList<>();
            for (MatsSocketEnvelopeDto envelope : envelopes) {
                try { // try-finally: MDC.remove..
                    MDC.put(MDC_MESSAGE_TYPE, (envelope.st != null ? envelope.t + ':' + envelope.st : envelope.t));
                    if (envelope.tid != null) {
                        MDC.put(MDC_TRACE_ID, envelope.tid);
                    }

                    // ?: Because I am paranoid, we assert state before processing each message
                    // NOTE: This should be redundant, as we've already checked before the pipeline loop.
                    if ((_matsSocketSessionId == null) || (_principal == null) || (_userId == null)) {
                        // -> Well, someone must have changed the code to not be correct anymore..!
                        closeWithPolicyViolation("Illegal state at checkpoint B.");
                        return;
                    }

                    // ----- We ARE authenticated, fer sure!

                    if ("PING".equals(envelope.t)) {
                        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                        replyEnvelope.t = "PONG";
                        commonPropsOnReceived(envelope, replyEnvelope, clientMessageReceivedTimestamp);

                        // Add PONG message to return-pipeline (should be sole message, really - not pipelined)
                        replyEnvelopes.add(replyEnvelope);
                        // The pong is handled, so go to next message
                        continue;
                    }

                    // ?: Did we have a sessionLost situation?
                    else if (sessionLost) {
                        // -> Yes, some other process has decided that the all/the rest of the messages should be
                        // dropped.
                        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                        replyEnvelope.t = "RECEIVED";
                        replyEnvelope.st = "SESSION_LOST";
                        commonPropsOnReceived(envelope, replyEnvelope, clientMessageReceivedTimestamp);
                        // Add this RECEIVED:<failed> message to return-pipeline.
                        replyEnvelopes.add(replyEnvelope);
                        // This is handled, so go to next.. (which also will be handled the same - failed)
                        continue;
                    }

                    // ?: Is this a SEND or REQUEST?
                    else if ("SEND".equals(envelope.t) || "REQUEST".equals(envelope.t)) {
                        handleSendOrRequest(clientMessageReceivedTimestamp, replyEnvelopes, envelope);
                        // The message is handled, so go to next message.
                        continue;
                    }

                    // Not known message..
                    else {
                        // -> Not expected message
                        log.error("Got an unknown message type [" + envelope.t + (envelope.st != null ? ":"
                                + envelope.st : "") + "] from client. Answering RECEIVED:ERROR.");
                        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
                        replyEnvelope.t = "RECEIVED";
                        replyEnvelope.st = "ERROR";
                        commonPropsOnReceived(envelope, replyEnvelope, clientMessageReceivedTimestamp);

                        // Add error message to return-pipeline
                        replyEnvelopes.add(replyEnvelope);
                        // The message is handled, so go to next message.
                        continue;
                    }
                }
                finally {
                    MDC.remove(MDC_MESSAGE_TYPE);
                }
            }

            // TODO: Store last messageSequenceId

            // Send all replies
            if (replyEnvelopes.size() > 0) {
                try {
                    String json = _envelopeListObjectWriter.writeValueAsString(replyEnvelopes);
                    webSocketSendText(json);
                }
                catch (JsonProcessingException e) {
                    throw new AssertionError("Huh, couldn't serialize message?!", e);
                }
                catch (IOException e) {
                    // TODO: Handle!
                    // TODO: At least store last messageSequenceId that we had ASAP. Maybe do it async?!
                    throw new AssertionError("Hot damn.", e);
                }
            }
            // ?: Notify about existing messages
            if (shouldNotifyAboutExistingMessages) {
                // -> Yes, so do it now.
                _matsSocketServer.getMessageToWebSocketForwarder().newMessagesInCsafNotify(this);
            }
        }
        finally {
            MDC.clear();
        }
    }

    private void commonPropsOnReceived(MatsSocketEnvelopeDto envelope, MatsSocketEnvelopeDto replyEnvelope,
            long clientMessageReceivedTimestamp) {
        replyEnvelope.cmseq = envelope.cmseq;
        replyEnvelope.tid = envelope.tid; // TraceId
        replyEnvelope.cid = envelope.cid; // CorrelationId
        replyEnvelope.cmcts = envelope.cmcts; // Set by client..
        replyEnvelope.cmrts = clientMessageReceivedTimestamp;
        replyEnvelope.cmrnn = _matsSocketServer.getMyNodename();
        replyEnvelope.mscts = System.currentTimeMillis();
        replyEnvelope.mscnn = _matsSocketServer.getMyNodename();
    }

    private void closeWithPolicyViolation(String reason) {
        closeSessionAndWebSocket(MatsSocketCloseCodes.VIOLATED_POLICY, reason);
    }

    private void closeSessionAndWebSocket(MatsSocketCloseCodes closeCode, String reason) {
        // :: Get copy of the WebSocket Session, before nulling it out
        Session webSocketSession = _webSocketSession;

        // :: Eagerly drop all authorization for session, so that this session object is ensured to be utterly useless.
        _authorization = null;
        _principal = null;
        _userId = null;
        // :: Also nulling out our references to the WebSocket, to ensure that it is impossible to send anything more
        _webSocketSession = null;
        _webSocketBasicRemote = null;

        // :: Deregister locally and Close MatsSocket Session in CSAF
        if (_matsSocketSessionId != null) {
            // Local deregister of live connection
            _matsSocketServer.deregisterLocalMatsSocketSession(_matsSocketSessionId, _connectionId);

            try {
                // CSAF close session
                _matsSocketServer.getClusterStoreAndForward().closeSession(_matsSocketSessionId);
            }
            catch (DataAccessException e) {
                log.warn("Could not close session in CSAF. This is unfortunate, as it then is technically possible to"
                        + " still reconnect to the session while this evidently was not the intention"
                        + " (only the same user can reconnect, though). However, the session scavenger"
                        + " will clean this lingering session out after some hours.", e);
            }
        }

        // :: Close the actual WebSocket
        DefaultMatsSocketServer.closeWebSocket(webSocketSession, closeCode, reason);

        // :: Finally, also clean sessionId and connectionId
        _matsSocketSessionId = null;
        _connectionId = null;
    }

    private boolean doAuthentication() {
        // ?: Do we have principal already?
        if (_principal == null) {
            // -> NO, we do not have principal
            // Ask SessionAuthenticator if it likes this Authorization header
            AuthenticationResult authenticationResult;
            try {
                authenticationResult = _sessionAuthenticator.initialAuthentication(_authenticationContext,
                        _authorization);
            }
            catch (RuntimeException re) {
                log.error("Got Exception when invoking SessionAuthenticator.initialAuthentication(..),"
                        + " Authorization header: " + _authorization, re);
                closeWithPolicyViolation("Authorization header not accepted on initial evaluation");
                return false;
            }
            if (authenticationResult instanceof AuthenticationResult_Authenticated) {
                // -> Authenticated
                AuthenticationResult_Authenticated result = (AuthenticationResult_Authenticated) authenticationResult;
                log.info("Authenticated with UserId: [" + _userId + "] and Principal [" + _principal + "]");
                _principal = result._principal;
                _userId = result._userId;
                // GOOD! Got new Principal and UserId.
                return true;
            }
            else {
                // -> Null, or any other result.
                log.error("We have not been authenticated! " + authenticationResult + ", Authorization header: "
                        + _authorization);
                closeWithPolicyViolation("Authorization header not accepted on initial evaluation");
                return false;
            }
        }
        else {
            // -> Yes, we already have principal
            // Ask SessionAuthenticator whether he still is happy with this Principal being authenticated, or supplies
            // a new Principal
            AuthenticationResult authenticationResult;
            try {
                authenticationResult = _sessionAuthenticator.reevaluateAuthentication(_authenticationContext,
                        _authorization, _principal);
            }
            catch (RuntimeException re) {
                log.error("Got Exception when invoking SessionAuthenticator.reevaluateAuthentication(..),"
                        + " Authorization header: " + _authorization, re);
                closeWithPolicyViolation("Authorization header not accepted on re-evaluation");
                return false;
            }
            if (authenticationResult instanceof AuthenticationResult_Authenticated) {
                // -> Authenticated anew
                AuthenticationResult_Authenticated result = (AuthenticationResult_Authenticated) authenticationResult;
                _principal = result._principal;
                _userId = result._userId;
                // GOOD! Got (potentially) new Principal and UserId.
                return true;
            }
            else if (authenticationResult instanceof AuthenticationResult_StillValid) {
                // -> The existing authentication is still valid
                log.debug("Still authenticated with UserId: [" + _userId + "] and Principal [" + _principal + "]");
                // GOOD! Existing auth still good.
                return true;
            }
            else {
                // -> Null, or any other result.
                log.error("We have not been authenticated! " + authenticationResult + ", Authorization header: "
                        + _authorization);
                closeWithPolicyViolation("Authorization header not accepted on re-evaluation");
                return false;
            }
        }
        // NOTE! There should NOT be a default return here!
    }

    private boolean handleHello(long clientMessageReceivedTimestamp, MatsSocketEnvelopeDto envelope) {
        log.info("MatsSocket HELLO!");
        // ?: Auth is required - should already have been processed
        if ((_principal == null) || (_authorization == null)) {
            // NOTE: This shall really never happen, as the implicit state machine should not have put us in this
            // situation. But just as an additional check.
            closeWithPolicyViolation("Missing authentication when evaluating HELLO message");
            return false;
        }

        _clientLibAndVersion = envelope.clv;
        if (_clientLibAndVersion == null) {
            closeWithPolicyViolation("Missing ClientLibAndVersion (clv) in HELLO envelope.");
            return false;
        }
        String appName = envelope.an;
        if (appName == null) {
            closeWithPolicyViolation("Missing AppName (an) in HELLO envelope.");
            return false;
        }
        String appVersion = envelope.av;
        if (appVersion == null) {
            closeWithPolicyViolation("Missing AppVersion (av) in HELLO envelope.");
            return false;
        }
        _appNameAndVersion = appName + ";" + appVersion;

        // ----- We're authenticated.

        // ?: Do the client assume that there is an already existing session?
        if (envelope.sid != null) {
            log.info("MatsSocketSession Reconnect requested to MatsSocketSessionId [" + envelope.sid + "]");
            // -> Yes, try to find it

            // TODO: Implement remote invalidation

            // :: Local invalidation of existing session.
            Optional<MatsSocketOnMessageHandler> existingSession = _matsSocketServer
                    .getRegisteredLocalMatsSocketSession(envelope.sid);
            // ?: Is there an existing local Session?
            if (existingSession.isPresent()) {
                log.info(" \\- Existing LOCAL Session found!");
                // -> Yes, thus you can use it.
                /*
                 * NOTE: If it is open - which it "by definition" should not be - we close the *previous*. The question
                 * of whether to close this or previous: We chose previous because there might be reasons where the
                 * client feels that it has lost the connection, but the server hasn't yet found out. The client will
                 * then try to reconnect, and that is ok. So we close the existing. Since it is always the server that
                 * creates session Ids and they are large and globally unique, AND since we've already authenticated the
                 * user so things should be OK, this ID is obviously the one the client got the last time. So if he
                 * really wants to screw up his life by doing reconnects when he does not need to, then OK.
                 */
                // ?: If the existing is open, then close it.
                if (existingSession.get()._webSocketSession.isOpen()) {
                    try {
                        existingSession.get()._webSocketSession.close(new CloseReason(CloseCodes.PROTOCOL_ERROR,
                                "Cannot have two MatsSockets with the same SessionId - closing the previous"));
                    }
                    catch (IOException e) {
                        log.warn("Got IOException when trying to close an existing WebSocket Session"
                                + " [MatsSocketSessionId: " + envelope.sid + ", existing WebSocket Session Id:["
                                + existingSession.get()._webSocketSession.getId() + "]] upon Client Reconnect."
                                + " Ignoring, probably just as well (that is, it had already closed).", e);
                    }
                }
                // You're allowed to use this, since the sessionId was already existing.
                _matsSocketSessionId = envelope.sid;
            }
            else {
                log.info(" \\- No existing local Session found, check CSAF..");
                // -> No, no local existing session, but is there an existing session in CSAF?
                try {
                    boolean sessionExists = _matsSocketServer.getClusterStoreAndForward()
                            .isSessionExists(envelope.sid);
                    // ?: Is there a CSAF Session?
                    if (sessionExists) {
                        log.info(" \\- Existing CSAF Session found!");
                        // -> Yes, there is a CSAF Session - so client can use this session
                        _matsSocketSessionId = envelope.sid;
                    }
                    else {
                        log.info(" \\- No existing Session found..");
                    }
                }
                catch (DataAccessException e) {
                    // TODO: Fixup
                    throw new AssertionError("Damn.", e);
                }
            }
        }

        // ?: Do we have a MatsSocketSessionId by now?
        if (_matsSocketSessionId == null) {
            // -> No, so make one.
            _matsSocketSessionId = DefaultMatsSocketServer.rnd(16);
        }

        // Register Session locally
        _matsSocketServer.registerLocalMatsSocketSession(this);
        // Register Session in CSAF
        try {
            _matsSocketServer.getClusterStoreAndForward().registerSessionAtThisNode(_matsSocketSessionId, _userId,
                    _connectionId);
        }
        catch (WrongUserException e) {
            // -> This should never occur with the normal MatsSocket clients, so this is probably hackery going on.
            log.error("We got WrongUserException when (evidently) trying to reconnect to existing SessionId."
                    + " This sniffs of hacking.", e);
            closeWithPolicyViolation("UserId of existing SessionId does not match currently logged in user.");
            return false;
        }
        catch (DataAccessException e) {
            // -> We could not talk to data store, so we cannot accept sessions at this point. Sorry.
            log.warn("Could not establish session in CSAF.", e);
            closeSessionAndWebSocket(MatsSocketCloseCodes.UNEXPECTED_CONDITION,
                    "Could not establish Session information in permanent storage, sorry.");
            return false;
        }

        // ----- We're now a live MatsSocketSession

        // Increase timeout to "prod timeout", now that client has said HELLO
        // TODO: Increase timeout, e.g. 75 seconds.
        _webSocketSession.setMaxIdleTimeout(30_000);
        // Set high limit for text, as we, don't want to be held back on the protocol side of things.
        _webSocketSession.setMaxTextMessageBufferSize(50 * 1024 * 1024);

        // :: Create reply WELCOME message

        MatsSocketEnvelopeDto replyEnvelope = new MatsSocketEnvelopeDto();
        // Stack it up with props
        replyEnvelope.t = "WELCOME";
        replyEnvelope.st = (_matsSocketSessionId.equalsIgnoreCase(envelope.sid) ? "RECONNECTED" : "NEW");
        replyEnvelope.sid = _matsSocketSessionId;
        replyEnvelope.cid = envelope.cid;
        replyEnvelope.tid = envelope.tid;
        replyEnvelope.cmcts = envelope.cmcts;
        replyEnvelope.cmrts = clientMessageReceivedTimestamp;
        replyEnvelope.mscts = System.currentTimeMillis();
        replyEnvelope.mscnn = _matsSocketServer.getMyNodename();

        // Pack it over to client
        List<MatsSocketEnvelopeDto> replySingleton = Collections.singletonList(replyEnvelope);
        try {
            String json = _envelopeListObjectWriter.writeValueAsString(replySingleton);
            webSocketSendText(json);
        }
        catch (JsonProcessingException e) {
            throw new AssertionError("Huh, couldn't serialize message?!", e);
        }
        catch (IOException e) {
            // TODO: Handle!
            // TODO: At least store last messageSequenceId that we had ASAP. Maybe do it async?!
            throw new AssertionError("Hot damn.", e);
        }
        return true;
    }

    private void handleSendOrRequest(long clientMessageReceivedTimestamp, List<MatsSocketEnvelopeDto> replyEnvelopes,
            MatsSocketEnvelopeDto envelope) {
        String eid = envelope.eid;
        log.info("  \\- " + envelope.t + " to:[" + eid + "], reply:[" + envelope.reid + "], msg:["
                + envelope.msg + "].");

        // TODO: Validate incoming message: cmseq, tid, whatever - reject if not OK.

        MatsSocketEndpointRegistration<?, ?, ?, ?> registration = _matsSocketServer
                .getMatsSocketEndpointRegistration(eid);
        IncomingAuthorizationAndAdapter incomingAuthEval = registration.getIncomingAuthEval();
        log.info("MatsSocketEndpointHandler for [" + eid + "]: " + incomingAuthEval);

        Object msg = deserialize((String) envelope.msg, registration.getMsIncomingClass());
        MatsSocketEndpointRequestContextImpl<?, ?> requestContext = new MatsSocketEndpointRequestContextImpl(
                _matsSocketServer, registration, _matsSocketSessionId, envelope,
                clientMessageReceivedTimestamp, _authorization, _principal, _userId, msg);

        MatsSocketEnvelopeDto handledEnvelope = new MatsSocketEnvelopeDto();
        try {
            incomingAuthEval.handleIncoming(requestContext, _principal, msg);
            // ?: If we insta-settled the request, then do a REPLY
            if (requestContext._settled) {
                // -> Yes, the handleIncoming settled the incoming message, so we insta-reply
                // NOTICE: We thus elide the "RECEIVED", as the client will handle the missing RECEIVED
                handledEnvelope.t = "REPLY";
                handledEnvelope.st = requestContext._resolved ? "RESOLVE" : "REJECT";
                handledEnvelope.msg = requestContext._matsSocketReplyMessage;
                log.info("handleIncoming(..) insta-settled the incoming message with"
                        + " [REPLY:" + handledEnvelope.st + "]");
            }
            else {
                // -> No, no insta-settling, so it was probably sent off to Mats
                handledEnvelope.t = "RECEIVED";
                handledEnvelope.st = "ACK";
                handledEnvelope.mmsts = System.currentTimeMillis();
            }
        }
        catch (MatsBackendRuntimeException | MatsMessageSendRuntimeException e) {
            // Evidently got problems talking to MQ or DB. This is a RETRY
            log.warn("Got problems running handleIncoming(..) due to MQ or DB - replying RECEIVED:RETRY to client.", e);
            // TODO: Should have an 'attempt' prop, mirroring from client - implicitly 1 if not set. Client increments.
            handledEnvelope = new MatsSocketEnvelopeDto();
            handledEnvelope.t = "RECEIVED";
            handledEnvelope.st = "RETRY";
            handledEnvelope.desc = e.getMessage();
        }
        catch (Throwable t) {
            // Evidently the handleIncoming didn't handle this message. This is a NACK.
            log.warn("handleIncoming(..) raised exception, assuming that it didn't like the incoming message"
                    + " - replying RECEIVED:NACK to client.", t);
            handledEnvelope = new MatsSocketEnvelopeDto();
            handledEnvelope.t = "RECEIVED";
            handledEnvelope.st = "NACK";
            handledEnvelope.desc = t.getMessage();
        }

        // .. add common props on the received message
        commonPropsOnReceived(envelope, handledEnvelope, clientMessageReceivedTimestamp);

        // Add RECEIVED message to "queue"
        replyEnvelopes.add(handledEnvelope);
    }

    private <T> T deserialize(String serialized, Class<T> clazz) {
        try {
            return _matsSocketServer.getJackson().readValue(serialized, clazz);
        }
        catch (JsonProcessingException e) {
            // TODO: Handle parse exceptions.
            throw new AssertionError("Damn", e);
        }
    }

    @Override
    public String toString() {
        return "MatsSocketSession{id='" + getId() + ",connId:'" + getConnectionId() + "'}";
    }

    private static class MatsSocketEndpointRequestContextImpl<MI, R> implements
            MatsSocketEndpointRequestContext<MI, R> {
        private final DefaultMatsSocketServer _matsSocketServer;
        private final MatsSocketEndpointRegistration _matsSocketEndpointRegistration;

        private final String _matsSocketSessionId;

        private final MatsSocketEnvelopeDto _envelope;
        private final long _clientMessageReceivedTimestamp;

        private final String _authorization;
        private final Principal _principal;
        private final String _userId;
        private final MI _incomingMessage;

        public MatsSocketEndpointRequestContextImpl(DefaultMatsSocketServer matsSocketServer,
                MatsSocketEndpointRegistration matsSocketEndpointRegistration, String matsSocketSessionId,
                MatsSocketEnvelopeDto envelope, long clientMessageReceivedTimestamp, String authorization,
                Principal principal, String userId, MI incomingMessage) {
            _matsSocketServer = matsSocketServer;
            _matsSocketEndpointRegistration = matsSocketEndpointRegistration;
            _matsSocketSessionId = matsSocketSessionId;
            _envelope = envelope;
            _clientMessageReceivedTimestamp = clientMessageReceivedTimestamp;
            _authorization = authorization;
            _principal = principal;
            _userId = userId;
            _incomingMessage = incomingMessage;
        }

        private R _matsSocketReplyMessage;
        private boolean _handled; // If either forwarded, or settled
        private boolean _settled; // If settled
        private boolean _resolved = true; // If neither resolve() nor reject() is invoked, it is a resolve.

        @Override
        public String getMatsSocketEndpointId() {
            return _envelope.eid;
        }

        @Override
        public String getAuthorizationHeader() {
            return _authorization;
        }

        @Override
        public Principal getPrincipal() {
            return _principal;
        }

        @Override
        public String getUserId() {
            return _userId;
        }

        @Override
        public MI getMatsSocketIncomingMessage() {
            return _incomingMessage;
        }

        @Override
        public boolean isRequest() {
            return _envelope.t.equals("REQUEST");
        }

        @Override
        public void forwardInteractiveUnreliable(MI matsMessage) {
            forwardCustom(matsMessage, customInit -> {
                customInit.to(getMatsSocketEndpointId());
                customInit.nonPersistent();
                customInit.interactive();
            });
        }

        @Override
        public void forwardInteractivePersistent(MI matsMessage) {
            forwardCustom(matsMessage, customInit -> {
                customInit.to(getMatsSocketEndpointId());
                customInit.interactive();
            });
        }

        @Override
        public void forwardCustom(MI matsMessage, InitiateLambda customInit) {
            if (_handled) {
                throw new IllegalStateException("Already handled.");
            }
            _matsSocketServer.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
                init.from("MatsSocketEndpoint." + _envelope.eid)
                        .traceId(_envelope.tid);
                if (isRequest()) {
                    ReplyHandleStateDto sto = new ReplyHandleStateDto(_matsSocketSessionId,
                            _matsSocketEndpointRegistration.getMatsSocketEndpointId(), _envelope.reid,
                            _envelope.cid, _envelope.cmseq, _envelope.cmcts, _clientMessageReceivedTimestamp,
                            System.currentTimeMillis(), _matsSocketServer.getMyNodename());
                    // Set ReplyTo parameter
                    init.replyTo(_matsSocketServer.getReplyTerminatorId(), sto);
                    // Invoke the customizer
                    customInit.initiate(init);
                    // Send the REQUEST message
                    init.request(matsMessage);
                }
                else {
                    // Invoke the customizer
                    customInit.initiate(init);
                    // Send the SEND message
                    init.send(matsMessage);
                }
            });
        }

        @Override
        public void resolve(R matsSocketResolveMessage) {
            if (!isRequest()) {
                throw new IllegalStateException("This is not a request, thus you cannot resolve nor reject it."
                        + " For a SEND, your options is to forward to Mats, or not forward (and just return).");
            }
            if (_handled) {
                throw new IllegalStateException("Already handled.");
            }
            _matsSocketReplyMessage = matsSocketResolveMessage;
            _handled = true;
            _settled = true;
            _resolved = true;
        }

        @Override
        public void reject(R matsSocketRejectMessage) {
            if (!isRequest()) {
                throw new IllegalStateException("This is not a request, thus you cannot resolve nor reject it."
                        + " For a SEND, your options is to forward to Mats, or not forward (and just return).");
            }
            if (_handled) {
                throw new IllegalStateException("Already handled.");
            }
            _matsSocketReplyMessage = matsSocketRejectMessage;
            _handled = true;
            _settled = true;
            _resolved = false;
        }
    }
}
