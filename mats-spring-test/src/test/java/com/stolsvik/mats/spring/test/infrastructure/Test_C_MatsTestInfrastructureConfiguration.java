package com.stolsvik.mats.spring.test.infrastructure;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.MatsFactory.MatsFactoryWrapper;
import com.stolsvik.mats.MatsFactory.MatsWrapper;
import com.stolsvik.mats.MatsInitiator;
import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.spring.test.MatsTestInfrastructureConfiguration;
import com.stolsvik.mats.test.MatsTestLatch;
import com.stolsvik.mats.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { MatsTestInfrastructureConfiguration.class })
public class Test_C_MatsTestInfrastructureConfiguration {

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
