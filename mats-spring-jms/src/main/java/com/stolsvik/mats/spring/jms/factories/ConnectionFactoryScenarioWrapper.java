package com.stolsvik.mats.spring.jms.factories;

import static com.stolsvik.mats.spring.MatsSpringConfiguration.LOG_PREFIX;

import javax.jms.ConnectionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

/**
 * A <code>ConnectionFactoryWrapper</code> which lazily decides which of the three {@link MatsScenario}s are active, and
 * produces the wrapper-target {@link ConnectionFactory} based on that - you most probably want to use
 * {@link JmsSpringConnectionFactoryProducer} to make an instance of this class, but you can configure it directly too.
 * 
 * @see JmsSpringConnectionFactoryProducer
 * @see MatsProfiles
 * @author Endre Stølsvik 2019-06-10 23:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class ConnectionFactoryScenarioWrapper
        extends ConnectionFactoryWrapper
        implements EnvironmentAware, BeanNameAware, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ConnectionFactoryScenarioWrapper.class);

    /**
     * A ConnectionFactory provider which can throw Exceptions - if it returns a
     * {@link ConnectionFactoryWithStartStopWrapper}, start() and stop() will be invoked on that, read more on its
     * JavaDoc.
     */
    @FunctionalInterface
    public interface ConnectionFactoryProvider {
        ConnectionFactory get(Environment springEnvironment) throws Exception;
    }

    /**
     * We need a way to decide between the three different {@link MatsScenario}s.
     */
    @FunctionalInterface
    public interface ScenarioDecider {
        MatsScenario decision(Environment springEnvironment);
    }

    /**
     * The three different Mats Scenarios that this ConnectionFactory wrapper juggles between based on the result of a
     * {@link ScenarioDecider}.
     * 
     * @see JmsSpringConnectionFactoryProducer
     */
    public enum MatsScenario {
        /**
         * @see JmsSpringConnectionFactoryProducer#regularConnectionFactory(ConnectionFactoryProvider)
         */
        REGULAR,

        /**
         * @see JmsSpringConnectionFactoryProducer#localhostConnectionFactory(ConnectionFactoryProvider)
         */
        LOCALHOST,

        /**
         * @see JmsSpringConnectionFactoryProducer#localVmConnectionFactory(ConnectionFactoryProvider)
         */
        LOCALVM
    }

    protected ConnectionFactoryProvider _regularConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localhostConnectionFactoryProvider;
    protected ConnectionFactoryProvider _localVmConnectionFactoryProvider;
    protected ScenarioDecider _scenarioDecider;

    /**
     * Constructor taking {@link ConnectionFactoryProvider}s for each of the three {@link MatsScenario}s and a
     * {@link ScenarioDecider} to decide which of these to employ - you most probably want to use
     * {@link JmsSpringConnectionFactoryProducer} to make one of these.
     */
    public ConnectionFactoryScenarioWrapper(ConnectionFactoryProvider regular, ConnectionFactoryProvider localhost,
            ConnectionFactoryProvider localvm, ScenarioDecider scenarioDecider) {
        _regularConnectionFactoryProvider = regular;
        _localhostConnectionFactoryProvider = localhost;
        _localVmConnectionFactoryProvider = localvm;
        _scenarioDecider = scenarioDecider;
    }

    protected String _beanName;

    @Override
    public void setBeanName(String name) {
        _beanName = name;
    }

    protected Environment _environment;

    @Override
    public void setEnvironment(Environment environment) {
        _environment = environment;
    }

    @Override
    public void setTargetConnectionFactory(ConnectionFactory targetConnectionFactory) {
        throw new IllegalStateException("You cannot set a target ConnectionFactory on a "
                + this.getClass().getSimpleName()
                + "; A set of suppliers will have to be provided in the constructor.");
    }

    protected ConnectionFactory _targetConnectionFactory;

    @Override
    public ConnectionFactory getTargetConnectionFactory() {
        return _targetConnectionFactory;
    }

    protected void createTargetConnectionFactoryBasedOnScenarioDecider() {
        ConnectionFactoryProvider provider;
        MatsScenario scenario = _scenarioDecider.decision(_environment);
        switch (scenario) {
            case REGULAR:
                provider = _regularConnectionFactoryProvider;
                break;
            case LOCALHOST:
                provider = _localhostConnectionFactoryProvider;
                break;
            case LOCALVM:
                provider = _localVmConnectionFactoryProvider;
                break;
            default:
                throw new AssertionError("Unknown MatsScenario enum value [" + scenario + "]!");
        }
        log.info("Creating ConnectionFactory decided by MatsScenario [" + scenario + "] from provider [" + provider
                + "].");

        // :: Actually get the ConnectionFactory.

        try {
            _targetConnectionFactory = provider.get(_environment);
        }
        catch (Exception e) {
            throw new CouldNotGetConnectionFactoryFromProviderException("Got problems when getting the"
                    + " ConnectionFactory from ConnectionFactoryProvider [" + provider + "] from Scenario [" + scenario
                    + "]", e);
        }

        // :: If the provided ConnectionFactory is "start-stoppable", then we must start it

        // ?: Is it a start-stoppable ConnectionFactory?
        if (_targetConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper) {
            // -> Yes, start-stoppable, so start it now (and set any returned target ConnectionFactory..)
            log.info("The provided ConnectionFactory from Scenario [" + scenario + "] implements "
                    + ConnectionFactoryWithStartStopWrapper.class.getSimpleName() + ", so invoking start(..) on it.");
            ConnectionFactoryWithStartStopWrapper startStopWrapper = (ConnectionFactoryWithStartStopWrapper) _targetConnectionFactory;
            try {
                ConnectionFactory targetConnectionFactory = startStopWrapper.start(_beanName);
                // ?: If the return value is non-null, we'll set it.
                if (targetConnectionFactory != null) {
                    // -> Yes, non-null, so set it per contract.
                    startStopWrapper.setTargetConnectionFactory(targetConnectionFactory);
                }
            }
            catch (Exception e) {
                throw new CouldNotStartConnectionFactoryWithStartStopWrapperException("Got problems starting the"
                        + " ConnectionFactoryWithStartStopWrapper [" + startStopWrapper + "] from Scenario [" + scenario
                        + "].", e);
            }
        }
    }

    protected static class CouldNotGetConnectionFactoryFromProviderException extends RuntimeException {
        public CouldNotGetConnectionFactoryFromProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStartConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStartConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected static class CouldNotStopConnectionFactoryWithStartStopWrapperException extends RuntimeException {
        public CouldNotStopConnectionFactoryWithStartStopWrapperException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ===== Implementation of SmartLifeCycle

    @Override
    public int getPhase() {
        // Returning a quite low number to be STARTED early, and STOPPED late.
        return -2_000_000;
    }

    private boolean _started;

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void start() {
        log.info(LOG_PREFIX + "SmartLifeCycle.start on [" + _beanName
                + "]: Creating Target ConnectionFactory based on ScenarioDecider [" + _scenarioDecider + "].");
        createTargetConnectionFactoryBasedOnScenarioDecider();
        _started = true;
    }

    @Override
    public boolean isRunning() {
        return _started;
    }

    @Override
    public void stop() {
        _started = false;
        if ((_targetConnectionFactory != null)
                && (_targetConnectionFactory instanceof ConnectionFactoryWithStartStopWrapper)) {
            try {
                log.info("The current target ConnectionFactory implements "
                        + ConnectionFactoryWithStartStopWrapper.class.getSimpleName()
                        + ", so invoking stop(..) on it.");
                ((ConnectionFactoryWithStartStopWrapper) _targetConnectionFactory).stop();
            }
            catch (Exception e) {
                throw new CouldNotStopConnectionFactoryWithStartStopWrapperException("Got problems stopping the"
                        + " current target ConnectionFactoryWithStartStopWrapper [" + _targetConnectionFactory + "].",
                        e);
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        log.info(LOG_PREFIX + "SmartLifeCycle.stop(callback) on [" + _beanName
                + "]: Stopping MatsLocalVmActiveMq's BrokerService.");
        stop();
        callback.run();
    }

}