package com.stolsvik.mats.spring.jms.tx;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import com.stolsvik.mats.MatsFactory;
import com.stolsvik.mats.impl.jms.JmsMatsFactory;
import com.stolsvik.mats.impl.jms.JmsMatsJmsSessionHandler;
import com.stolsvik.mats.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import com.stolsvik.mats.impl.jms.JmsMatsTransactionManager;
import com.stolsvik.mats.serial.MatsSerializer;
import com.stolsvik.mats.spring.EnableMats;

/**
 * Testing Spring DB Transaction management, supplying DataSource so that a DataSourceTransactionManager is made
 * internally.
 *
 * @author Endre Stølsvik 2019-05-06 21:35 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_OnlyDataSource extends Test_SpringManagedTx_H2Based_AbstractBase {

    private static final Logger log = LoggerFactory.getLogger(Test_SpringManagedTx_H2Based_OnlyDataSource.class);

    @Configuration
    @EnableMats
    static class SpringConfiguration_DataSource extends SpringConfiguration_AbstractBase {
        @Bean
        protected MatsFactory createMatsFactory(DataSource dataSource,
                ConnectionFactory connectionFactory, MatsSerializer<String> matsSerializer) {
            // Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
            JmsMatsJmsSessionHandler jmsSessionHandler = JmsMatsJmsSessionHandler_Pooling.create(connectionFactory);
            JmsMatsTransactionManager txMgrSpring = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                    dataSource);

            JmsMatsFactory<String> matsFactory = JmsMatsFactory.createMatsFactory(this.getClass().getSimpleName(),
                    "*testing*", jmsSessionHandler, txMgrSpring, matsSerializer);
            // For the MULTIPLE test scenario, it makes sense to test concurrency, so we go for 5.
            matsFactory.getFactoryConfig().setConcurrency(5);
            return matsFactory;
        }

    }
}
