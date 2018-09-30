package com.stolsvik.mats.impl.jms;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;

import com.stolsvik.mats.impl.jms.JmsMatsStage.JmsMatsStageProcessor;
import com.stolsvik.mats.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;

/**
 * Interface for implementing JMS Connection and JMS Session handling. This can implement both different connection
 * sharing mechanisms, and should implement some kind of Session pooling for initiators. It can also implement logic for
 * "is this connection up?" mechanisms, to close the gap whereby a JMS Connection is in a bad state but the application
 * has not noticed this yet.
 * <p>
 * The reason for session pooling for initiators is that a JMS Session can only be used for one thread, and since
 * initiators are shared throughout the code base, one initiator might be used by several threads at the same time.
 */
public interface JmsMatsJmsSessionHandler {

    /**
     * Should be invoked every time an Initiator wants to send a message - it will be returned after the message(s) is
     * sent.
     * 
     * @param initiator
     *            the initiator in question.
     * @return a {@link JmsSessionHolder} instance - which is not the same as any other SessionHolders concurrently in
     *         use (but it may be pooled, so after a {@link JmsSessionHolder#release()}, it may be returned to
     *         another invocation again).
     * @throws JmsMatsJmsException
     *             if there was a problem getting a Connection. Problems getting a Sessions (e.g. the current Connection
     *             is broken) should be internally handled (i.e. try to get a new Connection), except if it can be
     *             determined that the problem getting a Session is of a fundamental nature (i.e. the credentials can
     *             get a Connection, but cannot get a Session - which would be pretty absurd, but hey).
     */
    JmsSessionHolder getSessionHolder(JmsMatsInitiator<?> initiator) throws JmsMatsJmsException;

    /**
     * Will be invoked before the StageProcessor goes into its consumer loop - it will be closed once the Stage is
     * stopped, or if the Session "crashes", i.e. a method on Session or some downstream API throws an Exception.
     * 
     * @param processor
     *            the StageProcessor in question.
     * @return a {@link JmsSessionHolder} instance - which is unique for each call.
     * @throws JmsMatsJmsException
     *             if there was a problem getting a Connection. Problems getting a Sessions (e.g. the current Connection
     *             is broken) should be internally handled (i.e. try to get a new Connection), except if it can be
     *             determined that the problem getting a Session is of a fundamental nature (i.e. the credentials can
     *             get a Connection, but cannot get a Session - which would be pretty absurd, but hey).
     */
    JmsSessionHolder getSessionHolder(JmsMatsStageProcessor<?, ?, ?, ?> processor) throws JmsMatsJmsException;

    /**
     * A "sidecar object" for the JMS Session, so that additional stuff can be bound to it.
     */
    interface JmsSessionHolder {
        /**
         * Shall be invoked at these points, with the action to perform if it returns {@code false}:
         * <ol>
         * <li>(For StageProcessors) Before going into MessageConsumer.receive() - if {@code false} is returned,
         * {@link #close()} shall be invoked, and then a new SessionHolder shall be fetched. [This is to be able
         * to signal to the StageProcessor that the underlying Connection might have become unstable - start
         * afresh]</li>
         * <li>(For StageProcessors) After exiting from MessageConsumer.receive() - if {@code false} is returned,
         * rollback shall be performed, {@link #close()} shall be invoked, and then a new SessionHolder shall be
         * fetched. [This is to be able to signal to the StageProcessor that the underlying Connection might have become
         * unstable - start afresh] (NOTICE: The return from receive() shall obviously first be checked for null - which
         * is returned when the Consumer, Session or Connection is closed - which signifies that the normal check
         * for "running" shall be performed, and if it is, a new SessionHolder shall be fetched)].</li>
         * <li>(For StageProcessors and Initiators) Before committing any resources other than the JMS Session - if
         * {@code false} is returned, rollback shall be performed, {@link #close()} shall be invoked, and then a
         * new SessionHolder shall be fetched. [This is to tighten the gap between typically the DB commit and the JMS
         * commit: Before the DB is committed, an invocation to this method is performed. If this goes OK, then the DB
         * is committed and then the JMS Session is committed.]</li>
         * </ol>
         * TODO: Probably not: "If the connection to the server is not OK, the method shall throw a
         * {@link JmsMatsJmsException}." TODO: How will the concurrency between the consumer.receive()-call and
         * session.close()-call be? Register a "SessionShouldComeHomeListener" from the StageProcessor, which will be
         * invoked if another session calls crashed() (but first set "isSessionStillActive=false") - which does a "soft
         * stop()", "encouraging" a session.close()
         */
        boolean isSessionStillActive() throws JmsMatsJmsException;

        /**
         * @return the JMS Session. It will be the same instance every time.
         */
        Session getSession();

        /**
         * Employed by StageProcessors: This physically closes the Session, and removes it from the pool-Connection, and when all Sessions for a given pool-Connection is closed, the
         * pool-Connection is closed.
         */
        void close();

        /**
         * For Initiators: This returns the Session to the pool-Connection.
         */
        void release();

        /**
         * Notifies that a Session (or "downstream" consumer or producer) raised some exception - probably due to some
         * connectivity issues experienced as a JMSException while interacting with the JMS API, or because the
         * {@link JmsSessionHolder#isSessionStillActive()} returned {@code false}.
         * <p>
         * This should close and ditch the Session, then the SessionHandler should (semantically) mark the underlying
         * Connection as broken, and then then get all other "leasers" to come back with their sessions (close or
         * crash), so that the Connection can be closed. The leasers should then get a new session by
         * {@link JmsMatsJmsSessionHandler#getSessionHolder(JmsMatsStageProcessor)}, which will be based on a fresh
         * Connection.
         * <p>
         * NOTE: If a session comes back with "crashed", but it has already been "revoked" by the SessionHandler due to
         * another crash, this invocation should probably be equivalent to {@link #close()}, i.e. "come home as
         * agreed upon, whatever the state you are in".
         */
        void crashed(Throwable t);
    }

    /**
     * Utility interface for implementors: Abstracts away JMS Connection generation - useful if you need to provide
     * username and password, or some other connection parameters a la for IBM MQ.
     * <p>
     * Otherwise, the lambda can be as simple as
     * <code>(txContextKey) -> _jmsConnectionFactory.createConnection()</code>.
     */
    @FunctionalInterface
    interface JmsConnectionSupplier {
        Connection createJmsConnection(JmsMatsTxContextKey txContextKey) throws JMSException;
    }
}
