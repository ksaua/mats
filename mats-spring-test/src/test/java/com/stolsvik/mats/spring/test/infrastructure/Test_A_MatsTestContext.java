package com.stolsvik.mats.spring.test.infrastructure;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsFactory.MatsFactoryWrapper;
import com.stolsvik.mats.MatsFactory.MatsWrapper;
import com.stolsvik.mats.MatsInitiator;
import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.spring.test.MatsTestContext;
import com.stolsvik.mats.spring.test.MatsTestInfrastructureConfiguration;
import com.stolsvik.mats.spring.test.TestSpringMatsFactoryProvider;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_A_MatsTestContext {

    // The Mats Test Infrastructure

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsTestLatch _matsTestLatch;

    // Optionally depend on DataSource - IT SHALL NOT BE HERE!
    @Inject
    protected ObjectProvider<DataSource> _dataSource;

    @Test
    public void assertMatsInfrastructureInjected() {
        Assert.assertNotNull("MatsFactory should be in Spring context", _matsFactory);
        Assert.assertNotNull("MatsInitiator should be in Spring context", _matsInitiator);
        Assert.assertNotNull("MatsFuturizer should be in Spring context", _matsFuturizer);
        Assert.assertNotNull("MatsTestLatch should be in Spring context", _matsTestLatch);
    }

    @Test
    public void assertThatNoDataSourceIsCreated() {
        Assert.assertNull("DataSource should not be in Spring context", _dataSource.getIfAvailable());
    }

    /**
     * Extra-test that the MatsFactory we get injected is wrapped as expected by
     * {@link MatsTestInfrastructureConfiguration}, which employs {@link TestSpringMatsFactoryProvider} which wraps the
     * MatsFactory along with an ActiveMQ instance, and hooks MatsFactory shutdown to also close the ActiveMQ.
     */
    @Test
    public void test_MatsFactory_wrapped_from_TestSpringMatsFactoryProvider() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // The injected should be a wrapper, NOT implementation.
        Assert.assertTrue(_matsFactory instanceof MatsWrapper);
        Assert.assertFalse(_matsFactory instanceof JmsMatsFactory);

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = _matsFactory.unwrapFully();
        // The unwrapped instance should not be the same as the wrapper
        Assert.assertNotSame(_matsFactory, unWrappedMatsFactory);

        // The unwrapped instance should be the implementation, NOT a wrapper
        Assert.assertFalse(unWrappedMatsFactory instanceof MatsWrapper);
        Assert.assertTrue(unWrappedMatsFactory instanceof JmsMatsFactory);
    }

    /**
     * Extra-test to check that the wrapper works as expected: Multi-wrappings will still traverse down to the actual
     * implementation when employing the unwrapFully() method.
     */
    @Test
    public void test_double_wrapping_of_MatsFactory() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // Wrap the injected MatsFactory again - so now it is double-wrapped
        MatsFactory doubleWrapped = new MatsFactoryWrapper(_matsFactory);

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = doubleWrapped.unwrapFully();
        // The unwrapped instance should not be the same as the wrapper
        Assert.assertNotSame(doubleWrapped, unWrappedMatsFactory);

        // The unwrapped instance should be the implementation, NOT a wrapper
        Assert.assertFalse(unWrappedMatsFactory instanceof MatsWrapper);
        Assert.assertTrue(unWrappedMatsFactory instanceof JmsMatsFactory);
    }
}
